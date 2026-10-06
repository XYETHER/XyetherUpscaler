#include "mnn_fallback.hpp"

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#if defined(NDEBUG)
#define XY_LOG_PRINT(...) do { } while (0)
#else
#define XY_LOG_PRINT(...) __android_log_print(__VA_ARGS__)
#endif

namespace {

constexpr char kTag[] = "XyetherMnn";
constexpr int kTileSize = 256;
constexpr int kScale = 2;
constexpr int kOutputTile = kTileSize * kScale;
constexpr int kHalo = 16;
constexpr int kCore = kTileSize - 2 * kHalo;
constexpr int kMaxImageSide = 8192;
constexpr size_t kInputElements = static_cast<size_t>(3) * kTileSize * kTileSize;
constexpr size_t kOutputElements = static_cast<size_t>(3) * kOutputTile * kOutputTile;

struct MnnState {
    std::unique_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    MNN::Tensor* input = nullptr;
    MNN::Tensor* output = nullptr;
    std::string modelPath;
    std::string backendName;
    std::string cachePath;
    bool cacheWritten = false;
    int batchSize = 1;
};

// Live batch progress shared with Java through nativeGetUpscaleProgress().
std::atomic<int> gMnnBatchesDone{0};
std::atomic<int> gMnnBatchesTotal{0};
std::atomic<int> gMnnProgressActive{0};

MnnState& state() {
    static MnnState instance;
    return instance;
}

void releaseState() {
    MnnState& s = state();
    if (s.interpreter && s.session) s.interpreter->releaseSession(s.session);
    s.interpreter.reset();
    s.session = nullptr;
    s.input = nullptr;
    s.output = nullptr;
    s.modelPath.clear();
    s.backendName.clear();
    s.cachePath.clear();
    s.cacheWritten = false;
    s.batchSize = 1;
}

const char* backendName(MNNForwardType type) {
    switch (type) {
        case MNN_FORWARD_OPENCL: return "OpenCL";
        case MNN_FORWARD_VULKAN: return "Vulkan";
        default: return "Unknown";
    }
}

MNN::Session* createGpuSession(MNN::Interpreter* interpreter, MNNForwardType type) {
    MNN::ScheduleConfig config;
    MNN::BackendConfig backend;
    config.type = type;
    if (type == MNN_FORWARD_OPENCL) {
        // This is the LocalDream MNN GPU configuration: buffer memory + fast tune.
        config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    } else {
        // Vulkan accepts NONE/HEAVY/WIDE, not OpenCL's FAST flag.
        config.mode = MNN_GPU_TUNING_WIDE;
    }
    backend.precision = MNN::BackendConfig::Precision_Low;
    backend.power = MNN::BackendConfig::Power_High;
    config.backendConfig = &backend;
    return interpreter->createSession(config);
}

MNNForwardType actualBackend(MNN::Interpreter* interpreter, MNN::Session* session) {
    int forward = static_cast<int>(MNN_FORWARD_CPU);
    if (!interpreter || !session
            || !interpreter->getSessionInfo(session, MNN::Interpreter::BACKENDS, &forward)) {
        return MNN_FORWARD_CPU;
    }
    return static_cast<MNNForwardType>(forward);
}

bool hasExpectedShape(const MNN::Tensor* tensor, int batch, int height, int width) {
    return tensor != nullptr && tensor->batch() == batch && tensor->channel() == 3
            && tensor->height() == height && tensor->width() == width;
}

bool validateSession(MNN::Interpreter* interpreter, MNN::Session* session,
                     MNN::Tensor*& input, MNN::Tensor*& output) {
    input = interpreter->getSessionInput(session, nullptr);
    output = interpreter->getSessionOutput(session, nullptr);
    if (!input || !output || input->batch() <= 0 || input->batch() != output->batch()) return false;
    return hasExpectedShape(input, input->batch(), kTileSize, kTileSize)
            && hasExpectedShape(output, input->batch(), kOutputTile, kOutputTile);
}

std::string preferencePath(const std::string& modelPath) {
    return modelPath + ".gpu-backend-v2";
}

std::string readPreference(const std::string& modelPath) {
    std::ifstream in(preferencePath(modelPath));
    std::string value;
    in >> value;
    return value == "opencl" || value == "vulkan" ? value : "";
}

void writePreference(const std::string& modelPath, const char* value) {
    std::ofstream out(preferencePath(modelPath), std::ios::trunc);
    if (out) out << value << '\n';
}

std::string cachePathFor(const std::string& modelPath, MNNForwardType type) {
    return modelPath + (type == MNN_FORWARD_OPENCL ? ".opencl.mnnc" : ".vulkan.wide.mnnc");
}

void writeTile(const float* tile, int coreX, int coreY, int copyW, int copyH,
               int outputWidth, uint8_t* output) {
    const int sourceOffset = kHalo * kScale;
    const size_t plane = static_cast<size_t>(kOutputTile) * kOutputTile;
    for (int dy = 0; dy < copyH * kScale; ++dy) {
        const int tileY = sourceOffset + dy;
        const int outputY = coreY * kScale + dy;
        for (int dx = 0; dx < copyW * kScale; ++dx) {
            const int tileX = sourceOffset + dx;
            const size_t index = static_cast<size_t>(tileY) * kOutputTile + tileX;
            uint8_t* pixel = output + (static_cast<size_t>(outputY) * outputWidth
                    + coreX * kScale + dx) * 3;
            pixel[0] = static_cast<uint8_t>(std::lround(std::clamp(tile[index], 0.0f, 1.0f) * 255.0f));
            pixel[1] = static_cast<uint8_t>(std::lround(std::clamp(tile[plane + index], 0.0f, 1.0f) * 255.0f));
            pixel[2] = static_cast<uint8_t>(std::lround(std::clamp(tile[2 * plane + index], 0.0f, 1.0f) * 255.0f));
        }
    }
}

double benchmarkTile(MNN::Interpreter* interpreter, MNN::Session* session,
                    MNN::Tensor* input, int batchSize) {
    const size_t inputElements = kInputElements * static_cast<size_t>(batchSize);
    std::vector<float> sample(inputElements);
    for (size_t i = 0; i < sample.size(); ++i) sample[i] = static_cast<float>((i * 37u) % 251u) / 250.0f;
    std::unique_ptr<MNN::Tensor> host(MNN::Tensor::create<float>(
            {batchSize, 3, kTileSize, kTileSize}, sample.data(), MNN::Tensor::CAFFE));
    if (!host) return -1.0;

    // Warm once: excludes shader compilation/tuning from the comparison.
    input->copyFromHostTensor(host.get());
    if (interpreter->runSession(session) != 0) return -1.0;

    std::vector<double> samples;
    samples.reserve(3);
    for (int run = 0; run < 3; ++run) {
        input->copyFromHostTensor(host.get());
        const auto start = std::chrono::steady_clock::now();
        if (interpreter->runSession(session) != 0) return -1.0;
        const auto end = std::chrono::steady_clock::now();
        samples.push_back(std::chrono::duration<double, std::milli>(end - start).count());
    }
    std::sort(samples.begin(), samples.end());
    return samples[samples.size() / 2];
}

bool selectSession(MNN::Session* session, MNNForwardType type, const std::string& modelPath) {
    MnnState& s = state();
    MNN::Tensor* input = nullptr;
    MNN::Tensor* output = nullptr;
    if (!validateSession(s.interpreter.get(), session, input, output)) return false;
    s.session = session;
    s.input = input;
    s.output = output;
    s.modelPath = modelPath;
    s.backendName = backendName(type);
    s.batchSize = input->batch();
    s.cachePath = cachePathFor(modelPath, type);
    return true;
}

bool loadSelectedGpu(MNNForwardType type, const std::string& modelPath) {
    MnnState& s = state();
    s.interpreter.reset(MNN::Interpreter::createFromFile(modelPath.c_str()));
    if (!s.interpreter) return false;
    // LocalDream's persistent GPU cache management, kept separate by backend.
    s.cachePath = cachePathFor(modelPath, type);
    s.interpreter->setCacheFile(s.cachePath.c_str());
    MNN::Session* session = createGpuSession(s.interpreter.get(), type);
    if (!session || actualBackend(s.interpreter.get(), session) != type) {
        if (session) s.interpreter->releaseSession(session);
        s.interpreter.reset();
        return false;
    }
    if (!selectSession(session, type, modelPath)) {
        s.interpreter->releaseSession(session);
        s.interpreter.reset();
        return false;
    }
    return true;
}

}  // namespace

bool mnnFallbackIsLoaded() {
    const MnnState& s = state();
    return s.interpreter != nullptr && s.session != nullptr;
}

std::string mnnFallbackLoad(const std::string& modelPath) {
    if (modelPath.size() < 4 || modelPath.substr(modelPath.size() - 4) != ".mnn") {
        return "ERR: GPU fallback requires a .mnn model.";
    }
    MnnState& s = state();
    if (mnnFallbackIsLoaded() && s.modelPath == modelPath) {
        XY_LOG_PRINT(ANDROID_LOG_INFO, kTag, "GPU fallback session loaded: batch=%d", s.batchSize);
        return "OK: GPU fallback model already loaded";
    }
    releaseState();

    const std::string saved = readPreference(modelPath);
    if (saved == "opencl" && loadSelectedGpu(MNN_FORWARD_OPENCL, modelPath)) {
        return "OK: GPU fallback initialized (verified cached benchmark)";
    }
    if (saved == "vulkan" && loadSelectedGpu(MNN_FORWARD_VULKAN, modelPath)) {
        return "OK: GPU fallback initialized (verified cached benchmark)";
    }
    releaseState();

    // Benchmark only real GPU sessions. MNN's automatic CPU substitution is rejected.
    std::unique_ptr<MNN::Interpreter> probe(MNN::Interpreter::createFromFile(modelPath.c_str()));
    if (!probe) return "ERR: GPU fallback could not load the model.";

    MNN::Session* opencl = createGpuSession(probe.get(), MNN_FORWARD_OPENCL);
    if (!opencl || actualBackend(probe.get(), opencl) != MNN_FORWARD_OPENCL) {
        if (opencl) probe->releaseSession(opencl);
        opencl = nullptr;
    }
    MNN::Session* vulkan = createGpuSession(probe.get(), MNN_FORWARD_VULKAN);
    if (!vulkan || actualBackend(probe.get(), vulkan) != MNN_FORWARD_VULKAN) {
        if (vulkan) probe->releaseSession(vulkan);
        vulkan = nullptr;
    }
    if (!opencl && !vulkan) {
        return "ERR: GPU unavailable: no compatible GPU backend initialized; CPU fallback is disabled.";
    }

    double openclMs = -1.0;
    double vulkanMs = -1.0;
    MNN::Tensor* input = nullptr;
    MNN::Tensor* output = nullptr;
    if (opencl && validateSession(probe.get(), opencl, input, output)) {
        openclMs = benchmarkTile(probe.get(), opencl, input, input->batch());
    }
    if (vulkan && validateSession(probe.get(), vulkan, input, output)) {
        vulkanMs = benchmarkTile(probe.get(), vulkan, input, input->batch());
    }
    if (opencl) probe->releaseSession(opencl);
    if (vulkan) probe->releaseSession(vulkan);

    const bool useOpencl = openclMs > 0.0 && (vulkanMs < 0.0 || openclMs <= vulkanMs);
    const MNNForwardType selected = useOpencl ? MNN_FORWARD_OPENCL : MNN_FORWARD_VULKAN;
    if ((useOpencl && openclMs < 0.0) || (!useOpencl && vulkanMs < 0.0)) {
        return "ERR: GPU warm benchmark failed.";
    }
    if (!loadSelectedGpu(selected, modelPath)) {
        return "ERR: selected GPU backend could not be initialized.";
    }
    writePreference(modelPath, useOpencl ? "opencl" : "vulkan");
    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                        "GPU benchmark: opencl_ms=%.3f vulkan_ms=%.3f selected=%s model=%s",
                        openclMs, vulkanMs, backendName(selected), modelPath.c_str());
    return std::string("OK: GPU fallback initialized (") + backendName(selected)
            + " OpenCL " + std::to_string(openclMs)
            + " ms; Vulkan " + std::to_string(vulkanMs) + " ms)";
}

std::string mnnFallbackUpscale(const uint8_t* input, int width, int height, uint8_t* output,
                               size_t inputCapacity, size_t outputCapacity) {
    MnnState& s = state();
    if (!mnnFallbackIsLoaded()) return "ERR: GPU fallback model is not loaded.";
    if (width <= 0 || height <= 0 || width > kMaxImageSide || height > kMaxImageSide) {
        return "ERR: tiled input dimensions must be positive and no side may exceed 8192 pixels (received "
                + std::to_string(width) + "x" + std::to_string(height) + ").";
    }
    const size_t expectedInput = static_cast<size_t>(width) * height * 3;
    const int outputWidth = width * kScale;
    const int outputHeight = height * kScale;
    const size_t expectedOutput = static_cast<size_t>(outputWidth) * outputHeight * 3;
    if (inputCapacity < expectedInput || outputCapacity < expectedOutput) {
        return "ERR: input or output buffer has the wrong capacity.";
    }

    const int batchSize = s.batchSize;
    const size_t batchInputElements = kInputElements * static_cast<size_t>(batchSize);
    std::vector<float> tileInput(batchInputElements, 0.0f);
    std::unique_ptr<MNN::Tensor> inputHost(MNN::Tensor::create<float>(
            {batchSize, 3, kTileSize, kTileSize}, tileInput.data(), MNN::Tensor::CAFFE));
    std::unique_ptr<MNN::Tensor> outputHost(MNN::Tensor::create<float>(
            {batchSize, 3, kOutputTile, kOutputTile}, nullptr, MNN::Tensor::CAFFE));
    if (!inputHost || !outputHost) return "ERR: host tensor allocation failed.";

    struct TileWork {
        int coreX;
        int coreY;
        int copyW;
        int copyH;
    };
    std::vector<TileWork> pending;
    pending.reserve(static_cast<size_t>(batchSize));
    int tileCount = 0;

    {
        const int totalTiles =
                ((width + kCore - 1) / kCore) * ((height + kCore - 1) / kCore);
        gMnnBatchesTotal.store((totalTiles + batchSize - 1) / batchSize);
        gMnnBatchesDone.store(0);
        gMnnProgressActive.store(1);
    }
    struct MnnProgressGuard {
        ~MnnProgressGuard() { gMnnProgressActive.store(0); }
    } mnnProgressGuard;

    auto fillTile = [&](const TileWork& work, int slot) {
        const size_t slotOffset = kInputElements * static_cast<size_t>(slot);
        std::fill(tileInput.begin() + static_cast<std::ptrdiff_t>(slotOffset),
                  tileInput.begin() + static_cast<std::ptrdiff_t>(slotOffset + kInputElements), 0.0f);
        for (int ty = 0; ty < kTileSize; ++ty) {
            const int sourceY = work.coreY + ty - kHalo;
            if (sourceY < 0 || sourceY >= height) continue;
            for (int tx = 0; tx < kTileSize; ++tx) {
                const int sourceX = work.coreX + tx - kHalo;
                if (sourceX < 0 || sourceX >= width) continue;
                const uint8_t* source = input + (static_cast<size_t>(sourceY) * width + sourceX) * 3;
                const size_t tileIndex = static_cast<size_t>(ty) * kTileSize + tx;
                tileInput[slotOffset + tileIndex] = source[0] / 255.0f;
                tileInput[slotOffset + kTileSize * kTileSize + tileIndex] = source[1] / 255.0f;
                tileInput[slotOffset + 2 * kTileSize * kTileSize + tileIndex] = source[2] / 255.0f;
            }
        }
    };

    auto runBatch = [&]() -> bool {
        if (pending.empty()) return true;
        s.input->copyFromHostTensor(inputHost.get());
        if (s.interpreter->runSession(s.session) != 0) return false;
        s.output->copyToHostTensor(outputHost.get());
        const float* outputData = outputHost->host<float>();
        for (size_t slot = 0; slot < pending.size(); ++slot) {
            const TileWork& work = pending[slot];
            writeTile(outputData + slot * kOutputElements, work.coreX, work.coreY,
                      work.copyW, work.copyH, outputWidth, output);
            ++tileCount;
        }
        ++gMnnBatchesDone;
        pending.clear();
        return true;
    };

    for (int coreY = 0; coreY < height; coreY += kCore) {
        const int copyH = std::min(kCore, height - coreY);
        for (int coreX = 0; coreX < width; coreX += kCore) {
            pending.push_back({coreX, coreY, std::min(kCore, width - coreX), copyH});
            fillTile(pending.back(), static_cast<int>(pending.size() - 1));
            if (pending.size() == static_cast<size_t>(batchSize) && !runBatch()) {
                return "ERR: GPU inference failed.";
            }
        }
    }
    if (!runBatch()) return "ERR: GPU inference failed.";
    if (!s.cacheWritten) {
        s.interpreter->updateCacheFile(s.session);
        s.cacheWritten = true;
    }
    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag, "GPU inference complete: %dx%d tiles=%d batch=%d",
                        width, height, tileCount, s.batchSize);
    return "OK";
}

void mnnFallbackRelease() {
    releaseState();
}

int mnnFallbackProgressPercent() {
    if (!gMnnProgressActive.load(std::memory_order_relaxed)) return 0;
    const int total = gMnnBatchesTotal.load(std::memory_order_relaxed);
    if (total <= 0) return 0;
    const int done = gMnnBatchesDone.load(std::memory_order_relaxed);
    if (done <= 0) return 0;
    return done >= total ? 100 : done * 100 / total;
}

bool mnnFallbackProgressActive() {
    return gMnnProgressActive.load(std::memory_order_relaxed);
}

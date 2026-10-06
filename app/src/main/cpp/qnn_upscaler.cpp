#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <LibAppBuilder.hpp>
#include "mnn_fallback.hpp"
#if defined(NDEBUG)
#define XY_LOG_PRINT(...) do { } while (0)
#else
#define XY_LOG_PRINT(...) __android_log_print(__VA_ARGS__)
#endif

namespace {

constexpr char kTag[] = "XyetherEngine";
constexpr char kModelKeyBase[] = "xyether_anime_v3";
std::string gModelKey = kModelKeyBase;

// Live tile progress shared with Java through nativeGetUpscaleProgress().
std::atomic<int> gProgressTilesDone{0};
std::atomic<int> gProgressTilesTotal{0};
std::atomic<int> gPrimaryProgressActive{0};
constexpr int kTileSize = 256;
constexpr int kDefaultScale = 2;
int gScale = kDefaultScale;
// Runtime-selected output geometry; only 2x and 4x model contracts are accepted.
inline int outputTileSize() { return kTileSize * gScale; }
inline size_t outputElements() {
    return static_cast<size_t>(3) * outputTileSize() * outputTileSize();
}
inline size_t outputNativeBytes() { return outputElements(); }
inline size_t outputFloatBytes() { return outputElements() * sizeof(float); }
// The model has ten stride-1 3x3 convolutions (10px theoretical halo).
constexpr int kHalo = 16;
constexpr int kCoreSize = kTileSize - 2 * kHalo;
constexpr int kMaxInputSide = 2048;
constexpr int kMaxBatchSize = 32;
constexpr bool kUseSharedNativeIo = true;
constexpr int kAppBuilderProfilingLevel = 2;
constexpr size_t kInputElements = static_cast<size_t>(3) * kTileSize * kTileSize;
constexpr size_t kInputBytes = kInputElements;
constexpr size_t kInputFloatBytes = kInputElements * sizeof(float);
std::mutex gMutex;
std::unique_ptr<LibAppBuilder> gApp;
std::string gModelPath;
bool gLoaded = false;
bool gNativeInt8 = false;
int gBatchSize = 1;
bool gLogFirstNativeOutput = false;
float gNativeOutputScale = 0.0f;
int gNativeOutputOffset = 0;
std::array<uint8_t, 256> gNativeOutputLut{};

using Clock = std::chrono::steady_clock;

int64_t elapsedMicros(const Clock::time_point& start, const Clock::time_point& end) {
    return std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
}

void initializeNativeOutputLut() {
    for (int q = 0; q < 256; ++q) {
        const float value = std::clamp(
                (static_cast<float>(q) + static_cast<float>(gNativeOutputOffset)) * gNativeOutputScale,
                0.0f, 1.0f);
        gNativeOutputLut[static_cast<size_t>(q)] =
                static_cast<uint8_t>(std::lround(value * 255.0f));
    }
}

void logError(const std::string& message) {
    XY_LOG_PRINT(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

void dequantizeEightNative(const uint8_t* source, uint8_t* destination) {
#if defined(__aarch64__)
    const uint8x8_t quantized = vld1_u8(source);
    const int16x8_t shifted = vaddq_s16(
            vreinterpretq_s16_u16(vmovl_u8(quantized)),
            vdupq_n_s16(static_cast<int16_t>(gNativeOutputOffset)));
    const float32x4_t factor = vdupq_n_f32(gNativeOutputScale * 255.0f);
    const float32x4_t low = vmulq_f32(
            vcvtq_f32_s32(vmovl_s16(vget_low_s16(shifted))), factor);
    const float32x4_t high = vmulq_f32(
            vcvtq_f32_s32(vmovl_s16(vget_high_s16(shifted))), factor);
    const int16x8_t rounded = vcombine_s16(
            vqmovn_s32(vcvtnq_s32_f32(low)),
            vqmovn_s32(vcvtnq_s32_f32(high)));
    vst1_u8(destination, vqmovun_s16(rounded));
#else
    for (int i = 0; i < 8; ++i) {
        destination[i] = gNativeOutputLut[source[i]];
    }
#endif
}

void writeNativeTileRgb(const uint8_t* tileOutput, int coreX, int coreY,
                        int copyWidth, int copyHeight, int outputWidth, uint8_t* output) {
    const int sourceOffset = kHalo * gScale;
    const size_t plane = static_cast<size_t>(outputTileSize()) * outputTileSize();
    for (int dy = 0; dy < copyHeight * gScale; ++dy) {
        const int outputY = coreY * gScale + dy;
        const int tileY = sourceOffset + dy;
        const uint8_t* red = tileOutput + tileY * outputTileSize() + sourceOffset;
        const uint8_t* green = tileOutput + plane + tileY * outputTileSize() + sourceOffset;
        const uint8_t* blue = tileOutput + 2 * plane + tileY * outputTileSize() + sourceOffset;
        uint8_t* destination = output + (static_cast<size_t>(outputY) * outputWidth + coreX * gScale) * 3;
        int dx = 0;
#if defined(__aarch64__)
        for (; dx + 8 <= copyWidth * gScale; dx += 8) {
            uint8_t redPixels[8];
            uint8_t greenPixels[8];
            uint8_t bluePixels[8];
            dequantizeEightNative(red + dx, redPixels);
            dequantizeEightNative(green + dx, greenPixels);
            dequantizeEightNative(blue + dx, bluePixels);
            uint8x8x3_t rgb;
            rgb.val[0] = vld1_u8(redPixels);
            rgb.val[1] = vld1_u8(greenPixels);
            rgb.val[2] = vld1_u8(bluePixels);
            vst3_u8(destination + static_cast<size_t>(dx) * 3, rgb);
        }
#endif
        for (; dx < copyWidth * gScale; ++dx) {
            destination[static_cast<size_t>(dx) * 3] = gNativeOutputLut[red[dx]];
            destination[static_cast<size_t>(dx) * 3 + 1] = gNativeOutputLut[green[dx]];
            destination[static_cast<size_t>(dx) * 3 + 2] = gNativeOutputLut[blue[dx]];
        }
    }
}

void copyRgbRowToPlanar(const uint8_t* source, uint8_t* red, uint8_t* green,
                        uint8_t* blue, int pixelCount) {
    int x = 0;
#if defined(__aarch64__)
    for (; x + 8 <= pixelCount; x += 8) {
        const uint8x8x3_t rgb = vld3_u8(source + static_cast<size_t>(x) * 3);
        vst1_u8(red + x, rgb.val[0]);
        vst1_u8(green + x, rgb.val[1]);
        vst1_u8(blue + x, rgb.val[2]);
    }
#endif
    for (; x < pixelCount; ++x) {
        const uint8_t* pixel = source + static_cast<size_t>(x) * 3;
        red[x] = pixel[0];
        green[x] = pixel[1];
        blue[x] = pixel[2];
    }
}
std::string releaseLocked() {
    if (gApp && gLoaded) {
        gApp->ModelDestroy(gModelKey);
    }
    gApp.reset();
    gModelPath.clear();
    gLoaded = false;
    gNativeInt8 = false;
    gBatchSize = 1;
    gNativeOutputScale = 0.0f;
    gNativeOutputOffset = 0;
    gScale = kDefaultScale;
    gModelKey = kModelKeyBase;
    return "OK";
}

bool isExpectedShape(const std::vector<size_t>& shape, int batch, int channels, int height, int width) {
    return shape.size() == 4 &&
           shape[0] == static_cast<size_t>(batch) &&
           shape[1] == static_cast<size_t>(channels) &&
           shape[2] == static_cast<size_t>(height) &&
           shape[3] == static_cast<size_t>(width);
}

void freeOutputs(std::vector<uint8_t*>& buffers) {
    for (uint8_t* buffer : buffers) std::free(buffer);
    buffers.clear();
}

std::string loadLocked(const std::string& nativeLibDir, const std::string& modelPath,
                       bool useNativeInt8, float outputScale, int outputOffset,
                       int batchSize, int scale) {
    if (modelPath.size() < 4 || modelPath.substr(modelPath.size() - 4) != ".dlc") {
        return "ERR: QNN mode accepts a portable .dlc model; context binaries are created on-device.";
    }
    if (batchSize < 1 || batchSize > kMaxBatchSize ||
        (batchSize != 1 && batchSize != 2 && batchSize != 4 &&
         batchSize != 6 && batchSize != 8 && batchSize != 12 &&
         batchSize != 16 && batchSize != 24 && batchSize != 32)) {
        return "ERR: batch size must be 1, 2, 4, 6, 8, 12, 16, 24, or 32.";
    }
    if (scale != 2 && scale != 4) {
        return "ERR: model scale must be 2 or 4.";
    }
    if (gLoaded && gModelPath == modelPath && gNativeInt8 == useNativeInt8 &&
        gBatchSize == batchSize && gScale == scale &&
        (!useNativeInt8 || (gNativeOutputScale == outputScale && gNativeOutputOffset == outputOffset))) {
        return "OK: model already loaded";
    }
    if (useNativeInt8 && outputScale <= 0.0f) {
        return "ERR: INT8 model requires a positive output scale.";
    }

    releaseLocked();
    gScale = scale;
    const size_t slash = modelPath.find_last_of('/');
    std::string modelKeyName = modelPath.substr(slash == std::string::npos ? 0 : slash + 1);
    for (char& ch : modelKeyName) {
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
              (ch >= '0' && ch <= '9') || ch == '_')) ch = '_';
    }
    gModelKey = std::string(kModelKeyBase) + "_" + modelKeyName;
    setenv("ADSP_LIBRARY_PATH", nativeLibDir.c_str(), 1);
    setenv("LD_LIBRARY_PATH", nativeLibDir.c_str(), 1);

    gApp = std::make_unique<LibAppBuilder>();
    SetLogLevel(3);
    const bool profilingEnabled = SetProfilingLevel(kAppBuilderProfilingLevel);
    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                        "QNN profiling level=%d enabled=%d sharedNativeIo=%d",
                        kAppBuilderProfilingLevel, profilingEnabled ? 1 : 0,
                        kUseSharedNativeIo ? 1 : 0);

    const std::string backendPath = nativeLibDir + "/libQnnHtp.so";
    const std::string systemPath = nativeLibDir + "/libQnnSystem.so";
    if (!gApp->ModelInitialize(gModelKey, modelPath, backendPath, systemPath,
                               false, useNativeInt8 ? "native" : "float",
                               useNativeInt8 ? "native" : "float")) {
        releaseLocked();
        return "ERR: primary accelerator ModelInitialize failed. Check Snapdragon HTP support, the DLC, and the matched QAIRT runtime.";
    }

    const auto inputShapes = gApp->getInputShapes(gModelKey);
    const auto outputShapes = gApp->getOutputShapes(gModelKey);
    if (inputShapes.size() != 1 || outputShapes.size() != 1 ||
        !isExpectedShape(inputShapes[0], batchSize, 3, kTileSize, kTileSize) ||
        !isExpectedShape(outputShapes[0], batchSize, 3, outputTileSize(), outputTileSize())) {
        releaseLocked();
        return "ERR: DLC tensor contract must be batch[N,3,256,256] -> [N,3,outputScale*256,outputScale*256].";
    }

    const auto inputTypes = gApp->getInputDataType(gModelKey);
    const auto outputTypes = gApp->getOutputDataType(gModelKey);
    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                        "QNN IO contract: input=%s output=%s app_mode=%s inputBytes=%zu outputBytes=%zu",
                        inputTypes.empty() ? "unknown" : inputTypes[0].c_str(),
                        outputTypes.empty() ? "unknown" : outputTypes[0].c_str(),
                        useNativeInt8 ? "native-int8" : "float",
                        useNativeInt8 ? kInputBytes * static_cast<size_t>(batchSize) :
                                        kInputFloatBytes * static_cast<size_t>(batchSize),
                        useNativeInt8 ? outputNativeBytes() * static_cast<size_t>(batchSize) :
                                        outputFloatBytes() * static_cast<size_t>(batchSize));
    gModelPath = modelPath;
    gLoaded = true;
    gNativeInt8 = useNativeInt8;
    gBatchSize = batchSize;
    gNativeOutputScale = outputScale;
    gNativeOutputOffset = outputOffset;
    if (gNativeInt8) initializeNativeOutputLut();
    gLogFirstNativeOutput = true;
    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                        "primary accelerator ready: batch=%d tile=%d halo=%d core=%d", gBatchSize, kTileSize, kHalo, kCoreSize);
    return "OK: primary accelerator model initialized on this device";
}

struct BatchTileWork {
    int coreX;
    int coreY;
    int copyWidth;
    int copyHeight;
};

std::string upscaleBatchLocked(const uint8_t* input, int width, int height, uint8_t* output,
                               size_t inputCapacity, size_t outputCapacity) {
    const size_t inputBytes = static_cast<size_t>(width) * height * 3;
    const int outputWidth = width * gScale;
    const int outputHeight = height * gScale;
    const size_t outputBytes = static_cast<size_t>(outputWidth) * outputHeight * 3;
    if (inputCapacity < inputBytes || outputCapacity < outputBytes) {
        return "ERR: direct input or output buffer has the wrong capacity.";
    }

    const auto totalStart = Clock::now();
    int64_t profileSetupUs = 0;
    int64_t preprocessUs = 0;
    int64_t qnnInferenceUs = 0;
    int64_t postprocessUs = 0;
    int64_t outputFreeUs = 0;
    int tileCount = 0;
    int batchCount = 0;
    const size_t batchInputBytes = (gNativeInt8 ? kInputBytes : kInputFloatBytes) *
                                   static_cast<size_t>(gBatchSize);
    const size_t batchOutputBytes = (gNativeInt8 ? outputNativeBytes() : outputFloatBytes()) *
                                    static_cast<size_t>(gBatchSize);
    std::vector<uint8_t> tileInputNative;
    std::vector<float> tileInputFloat;
    std::vector<uint8_t> sharedNativeIo;
    if (gNativeInt8 && kUseSharedNativeIo) {
        sharedNativeIo.resize(batchInputBytes + batchOutputBytes);
    } else if (gNativeInt8) {
        tileInputNative.resize(batchInputBytes);
    } else {
        tileInputFloat.resize(kInputElements * static_cast<size_t>(gBatchSize));
    }
    std::vector<uint8_t*> inputBuffers(1);
    std::vector<uint8_t*> outputBuffers;
    std::vector<size_t> outputSizes;
    std::vector<BatchTileWork> pending;
    pending.reserve(static_cast<size_t>(gBatchSize));
    std::string perfProfile = "burst";

    const auto profileStart = Clock::now();
    if (!SetPerfProfileGlobal(perfProfile)) {
        return "ERR: primary accelerator burst performance profile could not be enabled.";
    }
    profileSetupUs = elapsedMicros(profileStart, Clock::now());
    struct ProfileGuard {
        ~ProfileGuard() { RelPerfProfileGlobal(); }
    } profileGuard;

    const int progressTotalTiles =
            ((width + kCoreSize - 1) / kCoreSize) * ((height + kCoreSize - 1) / kCoreSize);
    gProgressTilesTotal.store(progressTotalTiles);
    gProgressTilesDone.store(0);
    gPrimaryProgressActive.store(1);
    struct BatchProgressGuard {
        ~BatchProgressGuard() { gPrimaryProgressActive.store(0); }
    } batchProgressGuard;

    auto preprocessTile = [&](const BatchTileWork& work, int batchIndex) {
        const auto preprocessStart = Clock::now();
        const int tileStartX = work.coreX - kHalo;
        const int validStartX = std::max(0, tileStartX);
        const int validEndX = std::min(width, tileStartX + kTileSize);
        const int validWidth = validEndX - validStartX;
        const int tileOffsetX = validStartX - tileStartX;
        if (gNativeInt8) {
            uint8_t* tileBase = (kUseSharedNativeIo ? sharedNativeIo.data() : tileInputNative.data()) +
                                static_cast<size_t>(batchIndex) * kInputBytes;
            std::fill(tileBase, tileBase + kInputBytes, 0);
            for (int ty = 0; ty < kTileSize; ++ty) {
                const int sourceY = work.coreY + ty - kHalo;
                if (sourceY < 0 || sourceY >= height || validWidth <= 0) continue;
                const uint8_t* sourceRow = input + (static_cast<size_t>(sourceY) * width + validStartX) * 3;
                uint8_t* red = tileBase + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                uint8_t* green = tileBase + kTileSize * kTileSize +
                                 static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                uint8_t* blue = tileBase + 2 * kTileSize * kTileSize +
                                static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                copyRgbRowToPlanar(sourceRow, red, green, blue, validWidth);
            }
        } else {
            float* tileBase = tileInputFloat.data() +
                              static_cast<size_t>(batchIndex) * kInputElements;
            std::fill(tileBase, tileBase + kInputElements, 0.0f);
            for (int ty = 0; ty < kTileSize; ++ty) {
                const int sourceY = work.coreY + ty - kHalo;
                if (sourceY < 0 || sourceY >= height || validWidth <= 0) continue;
                const uint8_t* sourceRow = input + (static_cast<size_t>(sourceY) * width + validStartX) * 3;
                float* red = tileBase + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                float* green = tileBase + kTileSize * kTileSize +
                               static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                float* blue = tileBase + 2 * kTileSize * kTileSize +
                              static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                for (int tx = 0; tx < validWidth; ++tx) {
                    red[tx] = sourceRow[3 * tx] / 255.0f;
                    green[tx] = sourceRow[3 * tx + 1] / 255.0f;
                    blue[tx] = sourceRow[3 * tx + 2] / 255.0f;
                }
            }
        }
        preprocessUs += elapsedMicros(preprocessStart, Clock::now());
    };

    auto runBatch = [&]() -> bool {
        if (pending.empty()) return true;
        inputBuffers[0] = gNativeInt8
                ? (kUseSharedNativeIo ? sharedNativeIo.data() : tileInputNative.data())
                : reinterpret_cast<uint8_t*>(tileInputFloat.data());
        outputBuffers.clear();
        outputSizes.clear();
        if (gNativeInt8 && kUseSharedNativeIo) outputSizes.push_back(12345);
        const size_t expectedOutputBytes = batchOutputBytes;
        const auto qnnStart = Clock::now();
        const bool ok = gApp->ModelInference(
                gModelKey, inputBuffers, outputBuffers, outputSizes, perfProfile, 0,
                (gNativeInt8 && kUseSharedNativeIo) ? sharedNativeIo.size() : 0);
        qnnInferenceUs += elapsedMicros(qnnStart, Clock::now());
        if (!ok || outputBuffers.size() != 1 || outputSizes.size() != 1 ||
            outputSizes[0] < expectedOutputBytes) {
            if (!(gNativeInt8 && kUseSharedNativeIo)) freeOutputs(outputBuffers);
            else outputBuffers.clear();
            return false;
        }

        if (gLogFirstNativeOutput) {
            if (gNativeInt8) {
                const uint8_t* firstOutput = outputBuffers[0];
                const auto [minimum, maximum] = std::minmax_element(
                        firstOutput, firstOutput + outputElements() * static_cast<size_t>(gBatchSize));
                XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                                    "QNN batch native output: batch=%d returnedBytes=%zu expectedBytes=%zu min=%u max=%u sharedIo=%d",
                                    gBatchSize, outputSizes[0], expectedOutputBytes,
                                    static_cast<unsigned>(*minimum), static_cast<unsigned>(*maximum),
                                    kUseSharedNativeIo ? 1 : 0);
            } else {
                const float* firstOutput = reinterpret_cast<const float*>(outputBuffers[0]);
                const auto [minimum, maximum] = std::minmax_element(
                        firstOutput, firstOutput + outputElements() * static_cast<size_t>(gBatchSize));
                XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                                    "QNN batch float output: batch=%d returnedBytes=%zu expectedBytes=%zu min=%f max=%f",
                                    gBatchSize, outputSizes[0], expectedOutputBytes, *minimum, *maximum);
            }
            gLogFirstNativeOutput = false;
        }

        const auto postStart = Clock::now();
        for (size_t batchIndex = 0; batchIndex < pending.size(); ++batchIndex) {
            const BatchTileWork& work = pending[batchIndex];
            const uint8_t* nativeOutput = gNativeInt8
                    ? outputBuffers[0] + batchIndex * outputNativeBytes() : nullptr;
            const float* floatOutput = !gNativeInt8
                    ? reinterpret_cast<const float*>(outputBuffers[0]) + batchIndex * outputElements() : nullptr;
            if (gNativeInt8) {
                writeNativeTileRgb(nativeOutput, work.coreX, work.coreY,
                                   work.copyWidth, work.copyHeight, outputWidth, output);
            } else {
                const int sourceOffset = kHalo * gScale;
                for (int dy = 0; dy < work.copyHeight * gScale; ++dy) {
                    const int outputY = work.coreY * gScale + dy;
                    const int tileY = sourceOffset + dy;
                    for (int dx = 0; dx < work.copyWidth * gScale; ++dx) {
                        const int outputX = work.coreX * gScale + dx;
                        const int tileX = sourceOffset + dx;
                        const size_t tileHW = static_cast<size_t>(tileY) * outputTileSize() + tileX;
                        uint8_t* pixel = output + (static_cast<size_t>(outputY) * outputWidth + outputX) * 3;
                        for (int c = 0; c < 3; ++c) {
                            const float value = std::clamp(
                                    floatOutput[c * outputTileSize() * outputTileSize() + tileHW], 0.0f, 1.0f);
                            pixel[c] = static_cast<uint8_t>(std::lround(value * 255.0f));
                        }
                    }
                }
            }
        }
        postprocessUs += elapsedMicros(postStart, Clock::now());
        const auto freeStart = Clock::now();
        if (gNativeInt8 && kUseSharedNativeIo) outputBuffers.clear();
        else freeOutputs(outputBuffers);
        outputFreeUs += elapsedMicros(freeStart, Clock::now());
        tileCount += static_cast<int>(pending.size());
        ++batchCount;
        gProgressTilesDone.fetch_add(static_cast<int>(pending.size()));
        pending.clear();
        return true;
    };

    for (int coreY = 0; coreY < height; coreY += kCoreSize) {
        const int copyHeight = std::min(kCoreSize, height - coreY);
        for (int coreX = 0; coreX < width; coreX += kCoreSize) {
            pending.push_back({coreX, coreY, std::min(kCoreSize, width - coreX), copyHeight});
            preprocessTile(pending.back(), static_cast<int>(pending.size() - 1));
            if (pending.size() == static_cast<size_t>(gBatchSize) && !runBatch()) {
                return "ERR: batched primary accelerator graph inference failed.";
            }
        }
    }
    if (!runBatch()) return "ERR: final batched primary accelerator graph inference failed.";
    const int64_t totalUs = elapsedMicros(totalStart, Clock::now());
    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                        "XY_PROFILE_NATIVE input=%dx%d tiles=%d batch=%d batches=%d sharedIo=%d profile_ms=%.3f prep_ms=%.3f qnn_ms=%.3f post_ms=%.3f free_ms=%.3f total_ms=%.3f",
                        width, height, tileCount, gBatchSize, batchCount, kUseSharedNativeIo ? 1 : 0,
                        profileSetupUs / 1000.0, preprocessUs / 1000.0,
                        qnnInferenceUs / 1000.0, postprocessUs / 1000.0,
                        outputFreeUs / 1000.0, totalUs / 1000.0);
    return "OK";
}

std::string upscaleLocked(const uint8_t* input, int width, int height, uint8_t* output,
                          size_t inputCapacity, size_t outputCapacity) {
    if (!gLoaded || !gApp) return "ERR: QNN model is not loaded.";
    if (gBatchSize > 1) {
        return upscaleBatchLocked(input, width, height, output, inputCapacity, outputCapacity);
    }
    if (width <= 0 || height <= 0 || width > kMaxInputSide || height > kMaxInputSide) {
        return "ERR: input dimensions must be positive and no side may exceed 2048 pixels.";
    }

    const size_t inputBytes = static_cast<size_t>(width) * height * 3;
    const int outputWidth = width * gScale;
    const int outputHeight = height * gScale;
    const size_t outputBytes = static_cast<size_t>(outputWidth) * outputHeight * 3;
    if (inputCapacity < inputBytes || outputCapacity < outputBytes) {
        return "ERR: direct input or output buffer has the wrong capacity.";
    }

    const auto totalStart = Clock::now();
    int64_t profileSetupUs = 0;
    int64_t preprocessUs = 0;
    int64_t qnnInferenceUs = 0;
    int64_t postprocessUs = 0;
    int64_t outputFreeUs = 0;
    int tileCount = 0;
    std::vector<uint8_t> tileInputNative;
    std::vector<float> tileInputFloat;
    if (gNativeInt8) tileInputNative.resize(kInputBytes);
    else tileInputFloat.resize(kInputElements);
    std::vector<uint8_t*> inputBuffers(1);
    std::vector<uint8_t*> outputBuffers;
    std::vector<size_t> outputSizes;
    std::string perfProfile = "burst";

    const auto profileStart = Clock::now();
    if (!SetPerfProfileGlobal(perfProfile)) {
        return "ERR: primary accelerator burst performance profile could not be enabled.";
    }
    profileSetupUs = elapsedMicros(profileStart, Clock::now());
    struct ProfileGuard {
        ~ProfileGuard() { RelPerfProfileGlobal(); }
    } profileGuard;

    gProgressTilesTotal.store(((width + kCoreSize - 1) / kCoreSize) * ((height + kCoreSize - 1) / kCoreSize));
    gProgressTilesDone.store(0);
    gPrimaryProgressActive.store(1);
    struct SingleProgressGuard {
        ~SingleProgressGuard() { gPrimaryProgressActive.store(0); }
    } singleProgressGuard;

    for (int coreY = 0; coreY < height; coreY += kCoreSize) {
        const int copyHeight = std::min(kCoreSize, height - coreY);
        for (int coreX = 0; coreX < width; coreX += kCoreSize) {
            const int copyWidth = std::min(kCoreSize, width - coreX);

            const auto preprocessStart = Clock::now();
            const int tileStartX = coreX - kHalo;
            const int validStartX = std::max(0, tileStartX);
            const int validEndX = std::min(width, tileStartX + kTileSize);
            const int validWidth = validEndX - validStartX;
            const int tileOffsetX = validStartX - tileStartX;
            if (gNativeInt8) {
                std::fill(tileInputNative.begin(), tileInputNative.end(), 0);
                for (int ty = 0; ty < kTileSize; ++ty) {
                    const int sourceY = coreY + ty - kHalo;
                    if (sourceY < 0 || sourceY >= height || validWidth <= 0) continue;
                    const uint8_t* sourceRow = input + (static_cast<size_t>(sourceY) * width + validStartX) * 3;
                    uint8_t* red = tileInputNative.data() + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                    uint8_t* green = tileInputNative.data() + kTileSize * kTileSize
                            + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                    uint8_t* blue = tileInputNative.data() + 2 * kTileSize * kTileSize
                            + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                    copyRgbRowToPlanar(sourceRow, red, green, blue, validWidth);
                }
                inputBuffers[0] = tileInputNative.data();
            } else {
                std::fill(tileInputFloat.begin(), tileInputFloat.end(), 0.0f);
                for (int ty = 0; ty < kTileSize; ++ty) {
                    const int sourceY = coreY + ty - kHalo;
                    if (sourceY < 0 || sourceY >= height || validWidth <= 0) continue;
                    const uint8_t* sourceRow = input + (static_cast<size_t>(sourceY) * width + validStartX) * 3;
                    float* red = tileInputFloat.data() + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                    float* green = tileInputFloat.data() + kTileSize * kTileSize
                            + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                    float* blue = tileInputFloat.data() + 2 * kTileSize * kTileSize
                            + static_cast<size_t>(ty) * kTileSize + tileOffsetX;
                    for (int tx = 0; tx < validWidth; ++tx) {
                        red[tx] = sourceRow[3 * tx] / 255.0f;
                        green[tx] = sourceRow[3 * tx + 1] / 255.0f;
                        blue[tx] = sourceRow[3 * tx + 2] / 255.0f;
                    }
                }
                inputBuffers[0] = reinterpret_cast<uint8_t*>(tileInputFloat.data());
            }

            preprocessUs += elapsedMicros(preprocessStart, Clock::now());
            outputBuffers.clear();
            outputSizes.clear();
            const size_t expectedOutputBytes = gNativeInt8 ? outputNativeBytes() : outputFloatBytes();
            const auto qnnInferenceStart = Clock::now();
            if (!gApp->ModelInference(gModelKey, inputBuffers, outputBuffers, outputSizes, perfProfile) ||
                outputBuffers.size() != 1 || outputSizes.size() != 1 ||
                outputSizes[0] < expectedOutputBytes) {
                freeOutputs(outputBuffers);
                return "ERR: primary accelerator graph inference failed for a tile.";
            }

            qnnInferenceUs += elapsedMicros(qnnInferenceStart, Clock::now());
            const auto postprocessStart = Clock::now();
            if (gLogFirstNativeOutput) {
                if (gNativeInt8) {
                    const uint8_t* firstOutput = outputBuffers[0];
                    const auto [minimum, maximum] = std::minmax_element(firstOutput, firstOutput + outputElements());
                    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                                        "QNN native output: returnedBytes=%zu expectedBytes=%zu min=%u max=%u",
                                        outputSizes[0], expectedOutputBytes,
                                        static_cast<unsigned>(*minimum), static_cast<unsigned>(*maximum));
                } else {
                    const float* firstOutput = reinterpret_cast<const float*>(outputBuffers[0]);
                    const auto [minimum, maximum] = std::minmax_element(firstOutput, firstOutput + outputElements());
                    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                                        "QNN float output: returnedBytes=%zu expectedBytes=%zu min=%f max=%f",
                                        outputSizes[0], expectedOutputBytes, *minimum, *maximum);
                }
                gLogFirstNativeOutput = false;
            }
            const int sourceOffset = kHalo * gScale;
            if (gNativeInt8) {
                writeNativeTileRgb(outputBuffers[0], coreX, coreY,
                                   copyWidth, copyHeight, outputWidth, output);
            } else {
                const float* tileOutput = reinterpret_cast<const float*>(outputBuffers[0]);
                for (int dy = 0; dy < copyHeight * gScale; ++dy) {
                    const int outputY = coreY * gScale + dy;
                    const int tileY = sourceOffset + dy;
                    for (int dx = 0; dx < copyWidth * gScale; ++dx) {
                        const int outputX = coreX * gScale + dx;
                        const int tileX = sourceOffset + dx;
                        const size_t tileHW = static_cast<size_t>(tileY) * outputTileSize() + tileX;
                        uint8_t* pixel = output + (static_cast<size_t>(outputY) * outputWidth + outputX) * 3;
                        for (int c = 0; c < 3; ++c) {
                            const float value = std::clamp(
                                    tileOutput[c * outputTileSize() * outputTileSize() + tileHW], 0.0f, 1.0f);
                            pixel[c] = static_cast<uint8_t>(std::lround(value * 255.0f));
                        }
                    }
                }
            }
            postprocessUs += elapsedMicros(postprocessStart, Clock::now());
            const auto outputFreeStart = Clock::now();
            freeOutputs(outputBuffers);
            outputFreeUs += elapsedMicros(outputFreeStart, Clock::now());
            ++tileCount;
            ++gProgressTilesDone;
        }
    }
    const int64_t totalUs = elapsedMicros(totalStart, Clock::now());
    XY_LOG_PRINT(ANDROID_LOG_INFO, kTag,
                        "XY_PROFILE_NATIVE input=%dx%d tiles=%d profile_ms=%.3f prep_ms=%.3f qnn_ms=%.3f post_ms=%.3f free_ms=%.3f total_ms=%.3f",
                        width, height, tileCount,
                        profileSetupUs / 1000.0, preprocessUs / 1000.0,
                        qnnInferenceUs / 1000.0, postprocessUs / 1000.0,
                        outputFreeUs / 1000.0, totalUs / 1000.0);
    return "OK";
}

std::string javaString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring response(JNIEnv* env, const std::string& message) {
    return env->NewStringUTF(message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_xyether_upscaler_MainActivity_nativeLoadPrimaryModel(
        JNIEnv* env, jobject, jstring nativeLibDir, jstring modelPath,
        jboolean useNativeInt8, jfloat outputScale, jint outputOffset,
        jint batchSize, jint scale) {
    std::lock_guard<std::mutex> lock(gMutex);
    try {
        mnnFallbackRelease();
        return response(env, loadLocked(javaString(env, nativeLibDir), javaString(env, modelPath),
                                        useNativeInt8 == JNI_TRUE, outputScale, outputOffset, batchSize, scale));
    } catch (const std::exception& error) {
        logError(error.what());
        return response(env, std::string("ERR: ") + error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xyether_upscaler_MainActivity_nativeLoadGpuModel(
        JNIEnv* env, jobject, jstring modelPath) {
    std::lock_guard<std::mutex> lock(gMutex);
    try {
        releaseLocked();
        return response(env, mnnFallbackLoad(javaString(env, modelPath)));
    } catch (const std::exception& error) {
        logError(error.what());
        return response(env, std::string("ERR: ") + error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xyether_upscaler_MainActivity_nativeUpscaleRgb(
        JNIEnv* env, jobject, jobject inputBuffer, jint width, jint height, jobject outputBuffer) {
    std::lock_guard<std::mutex> lock(gMutex);
    // Validate before dispatch: every static-batch path must share this guard.
    // Bounded dimensions also make subsequent scaled int/size_t arithmetic safe.
    if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
        return response(env, "ERR: input dimensions must be positive and no side may exceed 8192 pixels.");
    }
    const int outputScale = gLoaded ? gScale : 2;
    if (outputScale < 1 || outputScale > 4) {
        return response(env, "ERR: unsupported output scale.");
    }
    if (!inputBuffer || !outputBuffer) {
        return response(env, "ERR: input and output buffers are required.");
    }
    const auto* input = static_cast<const uint8_t*>(env->GetDirectBufferAddress(inputBuffer));
    auto* output = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong inputCapacity = env->GetDirectBufferCapacity(inputBuffer);
    const jlong outputCapacity = env->GetDirectBufferCapacity(outputBuffer);
    if (!input || !output || inputCapacity < 0 || outputCapacity < 0) {
        return response(env, "ERR: native inference requires direct ByteBuffers.");
    }
    try {
        if (gLoaded) {
            return response(env, upscaleLocked(input, width, height, output,
                                               static_cast<size_t>(inputCapacity),
                                               static_cast<size_t>(outputCapacity)));
        }
        return response(env, mnnFallbackUpscale(input, width, height, output,
                                                 static_cast<size_t>(inputCapacity),
                                                 static_cast<size_t>(outputCapacity)));
    } catch (const std::exception& error) {
        logError(error.what());
        return response(env, std::string("ERR: ") + error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyether_upscaler_MainActivity_nativeReleaseModel(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    releaseLocked();
    mnnFallbackRelease();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xyether_upscaler_MainActivity_nativeGetUpscaleProgress(JNIEnv*, jobject) {
    if (gPrimaryProgressActive.load(std::memory_order_relaxed)) {
        const int total = gProgressTilesTotal.load(std::memory_order_relaxed);
        if (total <= 0) return 0;
        const int done = gProgressTilesDone.load(std::memory_order_relaxed);
        return done <= 0 ? 0 : (done >= total ? 10000 : done * 10000 / total);
    }
    return mnnFallbackProgressPercent();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyether_upscaler_MainActivity_nativeIsUpscaleBusy(JNIEnv*, jobject) {
    if (gPrimaryProgressActive.load(std::memory_order_relaxed)) return JNI_TRUE;
    return mnnFallbackProgressActive() ? JNI_TRUE : JNI_FALSE;
}

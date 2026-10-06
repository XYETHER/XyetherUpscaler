package com.xyether.upscaler;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.Os;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "XyetherUpscaler";
    private static final String FAST_VIDEO_BITRATE = "60M";

    private static final class ModelSpec {
        final String displayName;
        final String group1Dlc;
        final String group2Dlc;
        final String mnnModel;
        final boolean nativeInt8;
        final float outputScale;
        final int outputOffset;
        final String precision;
        final int batchSize;
        final int scale;

        ModelSpec(String displayName, String group1Dlc, String group2Dlc,
                  boolean nativeInt8, float outputScale, int outputOffset, String precision) {
            this(displayName, group1Dlc, group2Dlc, nativeInt8, outputScale, outputOffset, precision, 1, 2);
        }

        ModelSpec(String displayName, String group1Dlc, String group2Dlc,
                  boolean nativeInt8, float outputScale, int outputOffset,
                  String precision, int batchSize) {
            this(displayName, group1Dlc, group2Dlc, nativeInt8, outputScale, outputOffset, precision, batchSize, 2);
        }

        ModelSpec(String displayName, String group1Dlc, String group2Dlc,
                  boolean nativeInt8, float outputScale, int outputOffset,
                  String precision, int batchSize, int scale) {
            this(displayName, group1Dlc, group2Dlc, "", nativeInt8, outputScale,
                    outputOffset, precision, batchSize, scale);
        }

        ModelSpec(String displayName, String group1Dlc, String group2Dlc, String mnnModel,
                  boolean nativeInt8, float outputScale, int outputOffset,
                  String precision, int batchSize, int scale) {
            this.displayName = displayName;
            this.group1Dlc = group1Dlc;
            this.group2Dlc = group2Dlc;
            this.mnnModel = mnnModel;
            this.nativeInt8 = nativeInt8;
            this.outputScale = outputScale;
            this.outputOffset = outputOffset;
            this.precision = precision;
            this.batchSize = batchSize;
            this.scale = scale;
        }

        String dlcForGroup(int group) {
            return group == 2 ? group2Dlc : group1Dlc;
        }
    }

    private static ModelSpec model(String displayName, String stem, boolean nativeInt8,
                                   float outputScale, int outputOffset, String precision) {
        return new ModelSpec(displayName, stem + "_group1.dlc", stem + "_group2.dlc",
                nativeInt8, outputScale, outputOffset, precision);
    }

    private static final ModelSpec FULL_V3_INT8 = model(
            "Xyether Anime V3 (SUC) — INT8",
            "2x_XyetherAnimeV3", true, 0.009561454877f, -86, "INT8");
    private static final ModelSpec FULL_V3_FP16 = model(
            "Xyether Anime V3 (SUC) — FP16",
            "2x_XyetherAnimeV3Full_fp16", false, 0.0f, 0, "FP16");
    private static final ModelSpec FULL_V3_W4 = model(
            "Xyether Anime V3 (SUC) — W4A8 Experimental",
            "2x_XyetherAnimeV3Full_w4a8", true, 0.005398670677f, -41, "W4A8 Experimental");
    private static final ModelSpec FULL_V3_W2 = model(
            "Xyether Anime V3 (SUC) — W2A8 Experimental",
            "2x_XyetherAnimeV3Full_w2a8", true, 0.006051778793f, -55, "W2A8 Experimental");

    private static final ModelSpec COMPACT_V3_INT8 = model(
            "Xyether Anime V3 (Compact) — INT8",
            "2x_Animev3Compact_int8", true, 0.007619216107f, -60, "INT8");
    private static final ModelSpec COMPACT_V3_FP16 = model(
            "Xyether Anime V3 (Compact) — FP16",
            "2x_Animev3Compact_fp16", false, 0.0f, 0, "FP16");
    private static final ModelSpec COMPACT_V3_W4 = model(
            "Xyether Anime V3 (Compact) — W4A8 Experimental",
            "2x_Animev3Compact_w4a8", true, 0.291269958019f, -121, "W4A8 Experimental");
    private static final ModelSpec COMPACT_V3_W2 = model(
            "Xyether Anime V3 (Compact) — W2A8 Experimental",
            "2x_Animev3Compact_w2a8", true, 1.064214706421f, -138, "W2A8 Experimental");

    private static final ModelSpec QUICKSR_LARGE_FP16 = model(
            "QuickSRNet Large StrongV4 — FP16",
            "2x_QuickSRNet_large_fp16", false, 0.0f, 0, "FP16");
    private static final ModelSpec QUICKSR_LARGE_INT8 = model(
            "QuickSRNet Large StrongV4 — INT8",
            "2x_QuickSRNet_large_int8", true, 0.003913909197f, 0, "INT8");
    private static final ModelSpec QUICKSR_LARGE_W4 = model(
            "QuickSRNet Large StrongV4 — W4A8 Experimental",
            "2x_QuickSRNet_large_w4a8", true, 0.003913909197f, 0, "W4A8 Experimental");
    private static final ModelSpec QUICKSR_LARGE_W2 = model(
            "QuickSRNet Large StrongV4 — W2A8 Experimental",
            "2x_QuickSRNet_large_w2a8", true, 0.003763572779f, 0, "W2A8 Experimental");

    private static final ModelSpec QUICKSR_MEDIUM_FP16 = model(
            "QuickSRNet Medium StrongV4 — FP16",
            "2x_QuickSRNet_medium_fp16", false, 0.0f, 0, "FP16");
    private static final ModelSpec QUICKSR_MEDIUM_INT8 = model(
            "QuickSRNet Medium StrongV4 — INT8",
            "2x_QuickSRNet_medium_int8", true, 0.003913909197f, 0, "INT8");
    private static final ModelSpec QUICKSR_MEDIUM_W4 = model(
            "QuickSRNet Medium StrongV4 — W4A8 Experimental",
            "2x_QuickSRNet_medium_w4a8", true, 0.003788355272f, 0, "W4A8 Experimental");
    private static final ModelSpec QUICKSR_MEDIUM_W2 = model(
            "QuickSRNet Medium StrongV4 — W2A8 Experimental",
            "2x_QuickSRNet_medium_w2a8", true, 0.000869860698f, 0, "W2A8 Experimental");

    private static ModelSpec batchModel(String displayName, String stem, float outputScale,
                                        int outputOffset, int batchSize) {
        return batchModel(displayName, stem, outputScale, outputOffset, batchSize,
                "INT8 Batch" + batchSize + " Experimental");
    }

    private static ModelSpec batchModel(String displayName, String stem, float outputScale,
                                        int outputOffset, int batchSize, String precision) {
        return batchModel(displayName, stem, outputScale, outputOffset, batchSize, precision, 2);
    }

    private static ModelSpec batchModel(String displayName, String stem, float outputScale,
                                        int outputOffset, int batchSize, String precision, int scale) {
        return new ModelSpec(displayName, stem + "_group1.dlc", stem + "_group2.dlc",
                true, outputScale, outputOffset, precision, batchSize, scale);
    }

    private static ModelSpec fallbackBatchModel(String displayName, String stem, String mnnModel,
                                                float outputScale, int outputOffset, int batchSize) {
        return new ModelSpec(displayName, stem + "_group1.dlc", stem + "_group2.dlc", mnnModel,
                true, outputScale, outputOffset, "INT8 Batch" + batchSize + " Regular/PTQ", batchSize, 2);
    }

    private static final ModelSpec FULL_V3_BATCH2 = batchModel(
            "Xyether Anime V3 (SUC) — INT8 Batch2 Experimental",
            "2x_XyetherAnimeV3_batch2_int8", 0.009561454877f, -86, 2);
    private static final ModelSpec FULL_V3_BATCH4 = batchModel(
            "Xyether Anime V3 (SUC) — INT8 Batch4 Experimental",
            "2x_XyetherAnimeV3_batch4_int8", 0.009561454877f, -86, 4);
    private static final ModelSpec FULL_V3_BATCH6 = batchModel(
            "Xyether Anime V3 (SUC) — INT8 Batch6 Experimental",
            "2x_XyetherAnimeV3_batch6_int8", 0.009561454877f, -86, 6);
    private static final ModelSpec FULL_V3_BATCH8 = batchModel(
            "Xyether Anime V3 (SUC) — INT8 Batch8 Experimental",
            "2x_XyetherAnimeV3_batch8_int8", 0.009561454877f, -86, 8);
    private static final ModelSpec FULL_V3_BATCH16 = batchModel(
            "Xyether Anime V3 (SUC) — INT8 Batch16 Experimental",
            "2x_XyetherAnimeV3_batch16_int8", 0.009561454877f, -86, 16);
    private static final ModelSpec FULL_V3_BATCH24 = batchModel(
            "Xyether Anime V3 (SUC) — INT8 Batch24 Experimental",
            "2x_XyetherAnimeV3_batch24_int8", 0.009561454877f, -86, 24);
    private static final ModelSpec FULL_V3_BATCH32 = batchModel(
            "Xyether Anime V3 (SUC) — INT8 Batch32 Experimental",
            "2x_XyetherAnimeV3_batch32_int8", 0.009561454877f, -86, 32);

    private static final ModelSpec SUC_V3_INT8_BATCH8 = batchModel(
            "Xyether Anime Sharp Quality",
            "2x_XyetherAnimeV3_batch8_int8", 0.009561454877f, -86, 8,
            "INT8 Batch8 Regular/PTQ");
    private static final ModelSpec QUICKSR_LARGE_INT8_BATCH8 = fallbackBatchModel(
            "Xyether Anime Sharp Quality", "2x_QuickSRNet_large_batch8_int8",
            "xyether_anime_sharp_quality.mnn", 0.003921568859f, 0, 8);
    private static final ModelSpec IRL_QUALITY_INT8_BATCH8 = fallbackBatchModel(
            "Xyether IRL Quality (Beta ⚠️)", "2x_QuickSRNet_LARGE_IRL_batch8_int8",
            "xyether_irl_quality.mnn", 0.003921568859f, 0, 8);
    private static final ModelSpec SOFTV2_QUALITY_INT8_BATCH8 = fallbackBatchModel(
            "Xyether Anime Soft V2 Quality", "2x_QuickSRNet_LARGE_Softv2_batch8_int8",
            "xyether_soft_v2_quality.mnn", 0.003921568859f, 0, 8);
    private static final ModelSpec QUICKSR_MEDIUM_INT8_BATCH8 = fallbackBatchModel(
            "Xyether Anime Sharp Balanced", "2x_QuickSRNet_medium_StrongV4_balanced_batch8_int8",
            "xyether_anime_sharp_balanced.mnn", 0.003921568859f, 0, 8);

    private static final ModelSpec QUICKSR_MEDIUM_RARV3_BALANCED = fallbackBatchModel(
            "Xyether Anime Soft Balanced", "2x_QuickSRNet_medium_RARv3_balanced_batch8_int8",
            "xyether_anime_soft_balanced.mnn", 0.003921568859f, 0, 8);

    private static final ModelSpec QUICKSR_SMALL_PTQ_INT8 = fallbackBatchModel(
            "Xyether Anime Sharp Speed (BETA ⚠️)", "2x_QuickSRNet_small_StrongV4_batch16_ptq_int8",
            "xyether_anime_sharp_speed.mnn", 0.003913909197f, 0, 16);
    private static final ModelSpec QUICKSR_SMALL_RARV3_PTQ_INT8 = fallbackBatchModel(
            "Xyether Anime Soft Speed (BETA ⚠️)", "2x_QuickSRNet_small_RARv3_batch16_ptq_int8",
            "xyether_anime_soft_speed.mnn", 0.003913909197f, 0, 16);

    private static final ModelSpec QUICKSR_RARV3_PTQ_INT8 = fallbackBatchModel(
            "Xyether Anime Soft Quality", "2x_QuickSRNet_large_RARv3_batch8_ptq_int8",
            "xyether_anime_soft_quality.mnn", 0.003913909197f, 0, 8);

    private static final List<ModelSpec> BUILTIN_MODELS = Arrays.asList(
            QUICKSR_LARGE_INT8_BATCH8, IRL_QUALITY_INT8_BATCH8, SOFTV2_QUALITY_INT8_BATCH8,
            QUICKSR_MEDIUM_INT8_BATCH8, QUICKSR_SMALL_PTQ_INT8,
            QUICKSR_RARV3_PTQ_INT8, QUICKSR_MEDIUM_RARV3_BALANCED, QUICKSR_SMALL_RARV3_PTQ_INT8);

    static {
        System.loadLibrary("xyether_backend");
    }

    private String currentVideoPath = null;
    private String currentImagePath = null;
    private boolean imageMode = false;
    private String videoEncoder = "h264_mediacodec";
    private String videoBitrate = FAST_VIDEO_BITRATE;
    private String imageFormat = "PNG";
    private int imageJpegQuality = 95;
    private String mediaPicker = "samsung_gallery";
    private File modelDir;
    private File qnnRuntimeDir;
    private AutoCompleteTextView spinnerModel;
    private AutoCompleteTextView spinnerDlss;
    private VideoView videoPreview;
    private ImageView livePreview;
    private Bitmap currentLivePreview;
    private volatile boolean isCancelled = false;
    private volatile boolean qnnModelReady = false;
    private String activeModelName = "";
    private ModelSpec selectedModel = QUICKSR_LARGE_INT8_BATCH8;
    private final ExecutorService modelLoader = Executors.newSingleThreadExecutor();
    private final AtomicInteger modelLoadGeneration = new AtomicInteger();

    private native String nativeLoadPrimaryModel(String nativeLibraryDir, String modelPath,
                                             boolean nativeInt8, float outputScale,
                                             int outputOffset, int batchSize, int scale);
    private native String nativeLoadGpuModel(String modelPath);
    private native String nativeUpscaleRgb(ByteBuffer input, int width, int height, ByteBuffer output);
    private native void nativeReleaseModel();
    private native int nativeGetUpscaleProgress();
    private native boolean nativeIsUpscaleBusy();

    // Polls the native engines' live tile progress and drives the shared
    // progress bar + status line with percent and ETA. Stops writing as soon
    // as native work pauses for ~1s (frame gaps are shorter; stitching is
    // not), so completion messages stay visible.
    private void startNativeProgressPolling(AtomicBoolean active, TextView statusLabel,
                                            LinearProgressIndicator bar, long startedAtMs) {
        final Thread poller = new Thread(() -> {
            int lastShownPercent = -1;
            int idleTicks = 0;
            while (active.get() && !isFinishing() && !isDestroyed()) {
                final boolean busy = nativeIsUpscaleBusy();
                if (busy) {
                    idleTicks = 0;
                    final int raw = Math.max(0, Math.min(10000, nativeGetUpscaleProgress()));
                    final int percent = raw / 100;
                    final long elapsed = System.currentTimeMillis() - startedAtMs;
                    String etaText = "estimating…";
                    if (elapsed >= 1500 && raw >= 500) {
                        etaText = formatEta((long) ((elapsed / (raw / 10000f) - elapsed) / 1000));
                    }
                    final String finalEta = etaText;
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            bar.setProgressCompat(percent, true);
                            statusLabel.setText(String.format(Locale.US,
                                    "Processing: %d%% | ETA: %s", percent, finalEta));
                        }
                    });
                    lastShownPercent = percent;
                } else if (++idleTicks >= 4) {
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (lastShownPercent >= 0) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) bar.setProgressCompat(100, true);
                });
            }
        }, "XyetherProgressPoller");
        poller.setDaemon(true);
        poller.start();
    }

    private static String formatEta(long seconds) {
        if (seconds < 0) return "—";
        if (seconds >= 3600) return String.format(Locale.US, "%dh %02dm",
                seconds / 3600, (seconds % 3600) / 60);
        if (seconds >= 60) return String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60);
        return seconds + "s";
    }

    private String runMnnGpuSmokeTest(int scale) {
        return runMnnGpuDimensionSmokeTest(scale, 256, 256);
    }

    private String runMnnGpuDimensionSmokeTest(int scale, int inputWidth, int inputHeight) {
        final int outputWidth = inputWidth * scale;
        final int outputHeight = inputHeight * scale;
        ByteBuffer input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(outputWidth * outputHeight * 3).order(ByteOrder.nativeOrder());
        for (int y = 0; y < inputHeight; y++) {
            for (int x = 0; x < inputWidth; x++) {
                input.put((byte) x);
                input.put((byte) y);
                input.put((byte) ((x ^ y) & 0xFF));
            }
        }
        input.flip();
        String result = nativeUpscaleRgb(input, inputWidth, inputHeight, output);
        if (!result.startsWith("OK")) return result;
        long checksum = 1469598103934665603L;
        for (int i = 0; i < output.capacity(); i++) {
            checksum ^= output.get(i) & 0xFFL;
            checksum *= 1099511628211L;
        }
        return "OK: GPU " + inputWidth + "x" + inputHeight
                + " tile checksum=" + Long.toUnsignedString(checksum, 16);
    }

    private void applyBenchmarkSelection() {
        if (!isDebugBuild()) return;
        String benchmarkModel = getIntent().getStringExtra("benchmark_model");
        if ("sharp_quality".equals(benchmarkModel)) selectedModel = QUICKSR_LARGE_INT8_BATCH8;
        else if ("irl_quality".equals(benchmarkModel)) selectedModel = IRL_QUALITY_INT8_BATCH8;
        else if ("softv2_quality".equals(benchmarkModel)) selectedModel = SOFTV2_QUALITY_INT8_BATCH8;
        else if ("sharp_speed".equals(benchmarkModel)) selectedModel = QUICKSR_SMALL_PTQ_INT8;
        else if ("soft_quality".equals(benchmarkModel)) selectedModel = QUICKSR_RARV3_PTQ_INT8;
        else if ("soft_speed".equals(benchmarkModel)) selectedModel = QUICKSR_SMALL_RARV3_PTQ_INT8;
        else if ("sharp_balanced".equals(benchmarkModel)) selectedModel = QUICKSR_MEDIUM_INT8_BATCH8;
        else if ("soft_balanced".equals(benchmarkModel)) selectedModel = QUICKSR_MEDIUM_RARV3_BALANCED;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyBenchmarkSelection();
        setContentView(R.layout.activity_main);
        loadSettings();

        modelDir = new File(getFilesDir(), "qnn-models");
        qnnRuntimeDir = new File(getFilesDir(), "qnn-runtime");
        File cachedInput = new File(getCacheDir(), "input_orig.mp4");
        if (cachedInput.isFile()) currentVideoPath = cachedInput.getAbsolutePath();
        videoPreview = findViewById(R.id.video_preview);
        livePreview = findViewById(R.id.live_preview);
        spinnerModel = findViewById(R.id.spinner_model);
        spinnerDlss = findViewById(R.id.spinner_dlss);

        if (videoPreview != null) {
            videoPreview.setOnPreparedListener(mp -> mp.setVolume(0f, 0f));
        }

        List<String> dlssOptions = Arrays.asList("Off (100%)", "Quality (80%)", "Performance (60%)");
        spinnerDlss.setAdapter(getDropdownAdapter(dlssOptions));
        styleDropdown(spinnerDlss);
        spinnerDlss.setText(dlssOptions.get(0), false);

        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btn_info).setOnClickListener(v -> showInfoDialog());

        findViewById(R.id.btn_abort).setOnClickListener(v -> {
            isCancelled = true;
            ((TextView) findViewById(R.id.status_label)).setText("Stopping... Wrapping up video!");
            findViewById(R.id.btn_abort).setEnabled(false);
        });

        new Thread(this::logVideoEncoderCapabilities).start();
        new Thread(() -> {
            modelDir.mkdirs();
            copyBundledQnnRuntime();
            copyBundledModels();
            runOnUiThread(this::populateSpinner);
        }).start();

        findViewById(R.id.btn_open).setOnClickListener(v -> openMediaPicker(false));
        findViewById(R.id.btn_open_image).setOnClickListener(v -> openMediaPicker(true));

        findViewById(R.id.btn_run).setOnClickListener(v -> {
            if (imageMode) {
                if (currentImagePath == null) {
                    Toast.makeText(this, "Select an image first.", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else if (currentVideoPath == null) {
                Toast.makeText(this, "Select a video or image first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!qnnModelReady || activeModelName.isEmpty()) {
                String qnnStatus = ((TextView) findViewById(R.id.status_label)).getText().toString();
                Toast.makeText(this,
                        selectedModel.displayName + " is not ready. " + qnnStatus,
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (imageMode) {
                processImage(currentImagePath, activeModelName);
                return;
            }
            String dlssChoice = spinnerDlss.getText().toString();
            if (usesFastRawPipeline(currentVideoPath)) {
                processVideoFast(currentVideoPath, activeModelName, dlssChoice);
            } else {
                Log.i(TAG, "Using legacy frame path for non-16:9 input to preserve frame boundaries.");
                processVideoLegacy(currentVideoPath, activeModelName, dlssChoice);
            }
        });

        requirePermission();
    }

    private void setProcessingAction(boolean processing) {
        findViewById(R.id.btn_run).setVisibility(processing ? View.GONE : View.VISIBLE);
        findViewById(R.id.btn_abort).setVisibility(processing ? View.VISIBLE : View.GONE);
        findViewById(R.id.action_processing_label).setVisibility(processing ? View.VISIBLE : View.GONE);
        if (processing) findViewById(R.id.btn_abort).setEnabled(true);
    }

    private void logVideoEncoderCapabilities() {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!codec.isEncoder()) continue;
                for (String mime : codec.getSupportedTypes()) {
                    if (!"video/avc".equalsIgnoreCase(mime) && !"video/hevc".equalsIgnoreCase(mime)) continue;
                    MediaCodecInfo.EncoderCapabilities caps =
                            codec.getCapabilitiesForType(mime).getEncoderCapabilities();
                    if (caps == null) continue;
                    String quality = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? String.valueOf(caps.getQualityRange()) : "unsupported";
                    Log.i(TAG, "ENCODER_CAP name=" + codec.getName() + " mime=" + mime
                            + " cq=" + caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                            + " vbr=" + caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                            + " cbr=" + caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                            + " quality=" + quality);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to inspect video encoder capabilities", e);
        }
    }

    private TextView dropdownRow(String text, boolean popup) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(getColor(R.color.xyether_text));
        row.setTextSize(popup ? 16 : 15);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(20, popup ? 16 : 8, 20, popup ? 16 : 8);
        row.setMaxLines(popup ? 2 : 1);
        row.setEllipsize(TextUtils.TruncateAt.END);
        if (popup) row.setBackgroundColor(getColor(R.color.xyether_surface));
        return row;
    }

    private void styleDropdown(AutoCompleteTextView view) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.xyether_surface));
        background.setCornerRadius(18 * getResources().getDisplayMetrics().density);
        background.setStroke(1, getColor(R.color.xyether_outline));
        view.setDropDownBackgroundDrawable(background);
        view.setDropDownWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.92f));
        view.setDropDownHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.58f));
        view.setDropDownVerticalOffset(8);
    }

    private ArrayAdapter<String> getDropdownAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return dropdownRow(getItem(position), false);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return dropdownRow(getItem(position), true);
            }
            @Override public Filter getFilter() {
                return new Filter() {
                    @Override protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        results.values = items;
                        results.count = items.size();
                        return results;
                    }
                    @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                        notifyDataSetChanged();
                    }
                };
            }
        };
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("upscaler_settings", MODE_PRIVATE);
        videoEncoder = p.getString("video_encoder", "h264_mediacodec");
        videoBitrate = p.getString("video_bitrate", FAST_VIDEO_BITRATE);
        imageFormat = p.getString("image_format", "PNG");
        imageJpegQuality = p.getInt("image_jpeg_quality", 95);
        mediaPicker = p.getString("media_picker", "samsung_gallery");
    }

    private String videoEncoderLabel(String encoder) {
        if ("hevc_mediacodec".equals(encoder)) return "Hardware HEVC";
        if ("libx264".equals(encoder)) return "Software H.264";
        return "Hardware H.264";
    }

    private String videoEncodeArgs() {
        if ("hevc_mediacodec".equals(videoEncoder)) {
            return "-c:v hevc_mediacodec -b:v " + videoBitrate;
        }
        if ("libx264".equals(videoEncoder)) {
            return "-c:v libx264 -preset veryfast -b:v " + videoBitrate;
        }
        return "-c:v h264_mediacodec -b:v " + videoBitrate;
    }

    private void styleSettingsLabel(TextView view) {
        view.setTextColor(getColor(R.color.xyether_text_muted));
        view.setTextSize(12);
        view.setPadding(0, 14, 0, 4);
    }

    private void showSettingsDialog() {
        String[] encoderLabels = {"Hardware H.264", "Hardware HEVC", "Software H.264"};
        String[] encoderValues = {"h264_mediacodec", "hevc_mediacodec", "libx264"};
        String[] bitrateValues = {"12M", "24M", "40M", "60M", "100M"};
        String[] imageFormats = {"PNG", "JPG"};
        String[] mediaPickerLabels = {"Samsung Gallery", "System picker"};
        String[] mediaPickerValues = {"samsung_gallery", "system_picker"};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(getColor(R.color.xyether_surface));
        cardBackground.setCornerRadius(24 * getResources().getDisplayMetrics().density);
        cardBackground.setStroke(1, getColor(R.color.xyether_outline));
        root.setBackground(cardBackground);
        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("Settings");
        settingsTitle.setTextColor(getColor(R.color.xyether_text));
        settingsTitle.setTextSize(24);
        settingsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(settingsTitle);
        TextView encoderTitle = new TextView(this);
        encoderTitle.setText("Video encoder");
        styleSettingsLabel(encoderTitle);
        root.addView(encoderTitle);
        Spinner encoderSpinner = new Spinner(this);
        encoderSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, encoderLabels));
        for (int i = 0; i < encoderValues.length; i++) {
            if (encoderValues[i].equals(videoEncoder)) encoderSpinner.setSelection(i);
        }
        root.addView(encoderSpinner);

        TextView bitrateTitle = new TextView(this);
        bitrateTitle.setText("Video compression / bitrate");
        styleSettingsLabel(bitrateTitle);
        root.addView(bitrateTitle);
        Spinner bitrateSpinner = new Spinner(this);
        bitrateSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bitrateValues));
        for (int i = 0; i < bitrateValues.length; i++) {
            if (bitrateValues[i].equals(videoBitrate)) bitrateSpinner.setSelection(i);
        }
        root.addView(bitrateSpinner);

        TextView formatTitle = new TextView(this);
        formatTitle.setText("Image output format");
        styleSettingsLabel(formatTitle);
        root.addView(formatTitle);
        Spinner formatSpinner = new Spinner(this);
        formatSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, imageFormats));
        formatSpinner.setSelection("JPG".equals(imageFormat) ? 1 : 0);
        root.addView(formatSpinner);

        TextView pickerTitle = new TextView(this);
        pickerTitle.setText("Media picker");
        styleSettingsLabel(pickerTitle);
        root.addView(pickerTitle);
        Spinner pickerSpinner = new Spinner(this);
        pickerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, mediaPickerLabels));
        pickerSpinner.setSelection("system_picker".equals(mediaPicker) ? 1 : 0);
        root.addView(pickerSpinner);

        TextView qualityTitle = new TextView(this);
        qualityTitle.setText("JPG quality: " + imageJpegQuality);
        styleSettingsLabel(qualityTitle);
        root.addView(qualityTitle);
        SeekBar qualityBar = new SeekBar(this);
        qualityBar.setMax(100);
        qualityBar.setProgress(imageJpegQuality);
        qualityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.max(50, progress);
                qualityTitle.setText("JPG quality: " + value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(qualityBar);
        AdapterView.OnItemSelectedListener formatListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int visibility = position == 1 ? View.VISIBLE : View.GONE;
                qualityTitle.setVisibility(visibility);
                qualityBar.setVisibility(visibility);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        formatSpinner.setOnItemSelectedListener(formatListener);
        int qualityVisibility = "JPG".equals(imageFormat) ? View.VISIBLE : View.GONE;
        qualityTitle.setVisibility(qualityVisibility);
        qualityBar.setVisibility(qualityVisibility);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, 18, 0, 0);
        com.google.android.material.button.MaterialButton cancelButton = new com.google.android.material.button.MaterialButton(this);
        cancelButton.setText("Cancel");
        cancelButton.setAllCaps(false);
        cancelButton.setTextColor(getColor(R.color.xyether_text_muted));
        cancelButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.xyether_surface_soft)));
        cancelButton.setCornerRadius((int) (14 * getResources().getDisplayMetrics().density));
        com.google.android.material.button.MaterialButton saveButton = new com.google.android.material.button.MaterialButton(this);
        saveButton.setText("Save");
        saveButton.setAllCaps(false);
        saveButton.setTextColor(Color.WHITE);
        saveButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.xyether_primary)));
        saveButton.setCornerRadius((int) (14 * getResources().getDisplayMetrics().density));
        actions.addView(cancelButton, new LinearLayout.LayoutParams(0, 52, 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, 52, 1);
        saveParams.setMargins(12, 0, 0, 0);
        actions.addView(saveButton, saveParams);
        root.addView(actions);

        Dialog settingsDialog = new Dialog(this);
        settingsDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        settingsDialog.setContentView(root);
        settingsDialog.setCanceledOnTouchOutside(true);
        cancelButton.setOnClickListener(v -> settingsDialog.dismiss());
        saveButton.setOnClickListener(v -> {
            videoEncoder = encoderValues[encoderSpinner.getSelectedItemPosition()];
            videoBitrate = bitrateValues[bitrateSpinner.getSelectedItemPosition()];
            imageFormat = imageFormats[formatSpinner.getSelectedItemPosition()];
            mediaPicker = mediaPickerValues[pickerSpinner.getSelectedItemPosition()];
            imageJpegQuality = Math.max(50, qualityBar.getProgress());
            getSharedPreferences("upscaler_settings", MODE_PRIVATE).edit()
                    .putString("video_encoder", videoEncoder)
                    .putString("video_bitrate", videoBitrate)
                    .putString("image_format", imageFormat)
                    .putString("media_picker", mediaPicker)
                    .putInt("image_jpeg_quality", imageJpegQuality)
                    .apply();
            settingsDialog.dismiss();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
        settingsDialog.show();
        if (settingsDialog.getWindow() != null) {
            settingsDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            settingsDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            settingsDialog.getWindow().setDimAmount(0.58f);
            settingsDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showInfoDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_info);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        dialog.findViewById(R.id.btn_discord).setOnClickListener(v -> openLink("https://discord.com/invite/tQP27ew3bQ"));
        dialog.findViewById(R.id.btn_telegram).setOnClickListener(v -> openLink("https://t.me/xyethertwixtors"));
        dialog.findViewById(R.id.btn_tiktok).setOnClickListener(v -> openLink("https://www.tiktok.com/@xyether_"));
        dialog.findViewById(R.id.btn_youtube).setOnClickListener(v -> openLink("https://www.youtube.com/@xyether2"));
        dialog.findViewById(R.id.btn_close_info).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.58f);
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void openLink(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private boolean copyAssetToFile(String assetName, File outFile) {
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void copyBundledQnnRuntime() {
        try {
            qnnRuntimeDir.mkdirs();
            File[] staleFiles = qnnRuntimeDir.listFiles();
            if (staleFiles != null) {
                for (File stale : staleFiles) {
                    if (stale.isFile()) stale.delete();
                }
            }
            String[] assets = getAssets().list("qnnlibs");
            if (assets == null || assets.length == 0) {
                throw new IllegalStateException("qnnlibs asset directory is empty");
            }
            for (String file : assets) {
                File target = new File(qnnRuntimeDir, file);
                if (!copyAssetToFile("qnnlibs/" + file, target)) {
                    throw new IllegalStateException("failed to copy " + file);
                }
                target.setReadable(true, true);
                target.setExecutable(true, true);
            }
            qnnRuntimeDir.setReadable(true, true);
            qnnRuntimeDir.setExecutable(true, true);
            if (isDebugBuild()) Log.i(TAG, "Local runtime prepared (" + assets.length + " files)");
        } catch (Exception e) {
            Log.e(TAG, "Unable to install local runtime", e);
        }
    }

    private void copyBundledModels() {
        try {
            File legacyModel = new File(modelDir, "2x_XyetherAnimeV3.dlc");
            if (legacyModel.exists() && !legacyModel.delete()) {
                Log.w(TAG, "Could not remove legacy single-DLC asset");
            }
            copyBundledModelDirectory("models", ".dlc");
            copyBundledModelDirectory("mnn", ".mnn");
        } catch (Exception e) {
            Log.e(TAG, "Unable to install bundled inference models", e);
        }
    }

    private void copyBundledModelDirectory(String directory, String suffix) throws java.io.IOException {
        String[] assets = getAssets().list(directory);
        if (assets == null) return;
        for (String file : assets) {
            if (file.endsWith(suffix) && !copyAssetToFile(directory + "/" + file, new File(modelDir, file))) {
                throw new java.io.IOException("failed to copy " + directory + "/" + file);
            }
        }
    }

    private String getSocModel() {
        String soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : Build.HARDWARE;
        if (soc == null || soc.isEmpty()) soc = Build.HARDWARE;
        return soc == null ? "UNKNOWN" : soc.toUpperCase(Locale.ROOT);
    }

    private int getQnnGroup() {
        String soc = getSocModel();
        switch (soc) {
            // Group 2 is the high-performance group. SM8650 is deliberately
            // included here per the app deployment plan.
            case "SM8650":
            case "SM8650P":
            case "SM8750":
            case "SM8750P":
            case "SM8850":
            case "SM8850P":
            case "SM8845":
                return 2;
            default:
                return 1;
        }
    }

    private boolean shouldUseQnn() {
        if (isDebugBuild() && getIntent().getBooleanExtra("force_mnn", false)) return false;
        String soc = getSocModel();
        return soc.startsWith("SM") || soc.contains("SNAPDRAGON");
    }

    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private String getSelectedDlcName() {
        return selectedModel.dlcForGroup(getQnnGroup());
    }

    private void populateSpinner() {
        List<String> modelNames = new ArrayList<>();
        for (ModelSpec model : BUILTIN_MODELS) modelNames.add(model.displayName);
        spinnerModel.setAdapter(getDropdownAdapter(modelNames));
        styleDropdown(spinnerModel);
        spinnerModel.setText(selectedModel.displayName, false);
        spinnerModel.setOnItemClickListener((parent, view, position, id) -> {
            selectedModel = BUILTIN_MODELS.get(position);
            compileModel();
        });
        compileModel();
    }

    private void compileModel() {
        final ModelSpec modelSpec = selectedModel;
        final int loadGeneration = modelLoadGeneration.incrementAndGet();
        activeModelName = modelSpec.displayName;
        qnnModelReady = false;
        final String socModel = getSocModel();
        final int qnnGroup = getQnnGroup();
        final boolean preferQnn = shouldUseQnn();
        final File qnnModel = new File(modelDir, modelSpec.dlcForGroup(qnnGroup));
        final File mnnModel = new File(modelDir, modelSpec.mnnModel);

        findViewById(R.id.progress_container).setVisibility(View.VISIBLE);
        findViewById(R.id.btn_run).setEnabled(false);
        if (!preferQnn && !mnnModel.isFile()) {
            ((TextView) findViewById(R.id.status_label)).setText(
                    modelSpec.displayName + " is unavailable on this device.");
            findViewById(R.id.btn_run).setEnabled(true);
            return;
        }
        if (preferQnn && !qnnModel.isFile() && !mnnModel.isFile()) {
            ((TextView) findViewById(R.id.status_label)).setText(
                    modelSpec.displayName + " is unavailable on this device.");
            findViewById(R.id.btn_run).setEnabled(true);
            return;
        }

        String loading = "Preparing model on this device…";
        ((TextView) findViewById(R.id.status_label)).setText(loading);
        modelLoader.execute(() -> {
            if (loadGeneration != modelLoadGeneration.get()) return;
            String result;
            String backend;
            if (preferQnn && qnnModel.isFile()) {
                backend = "device accelerator";
                result = nativeLoadPrimaryModel(qnnRuntimeDir.getAbsolutePath(), qnnModel.getAbsolutePath(),
                        modelSpec.nativeInt8, modelSpec.outputScale, modelSpec.outputOffset,
                        modelSpec.batchSize, modelSpec.scale);
                if (!result.startsWith("OK:") && mnnModel.isFile()) {
                    String qnnFailure = result;
                    backend = "GPU fallback";
                    result = nativeLoadGpuModel(mnnModel.getAbsolutePath());
                    if (isDebugBuild()) Log.w(TAG, "Primary accelerator load failed; GPU fallback selected: " + qnnFailure);
                }
            } else {
                backend = "GPU fallback";
                result = nativeLoadGpuModel(mnnModel.getAbsolutePath());
            }
            boolean ready = result.startsWith("OK:");
            if (ready && isDebugBuild() && getIntent().getBooleanExtra("verify_mnn_gpu", false)
                    && backend.contains("fallback")) {
                String smoke = runMnnGpuSmokeTest(modelSpec.scale);
                Log.i(TAG, "GPU_TILE_SMOKE_RESULT: " + smoke);
                if (!smoke.startsWith("OK:")) {
                    result = "ERR: GPU tile smoke test failed: " + smoke;
                    ready = false;
                }
                if (ready && isDebugBuild() && getIntent().getBooleanExtra("verify_mnn_4k", false)) {
                    String smoke4k = runMnnGpuDimensionSmokeTest(modelSpec.scale, 3840, 2160);
                    Log.i(TAG, "GPU_4K_TILE_RESULT: " + smoke4k);
                    if (!smoke4k.startsWith("OK:")) {
                        result = "ERR: GPU 4K tile smoke test failed: " + smoke4k;
                        ready = false;
                    }
                }
            }
            if (isDebugBuild()) Log.i(TAG, "MODEL_RUNTIME=" + backend + " DEVICE=" + socModel
                    + " PROFILE=" + modelSpec.displayName + " RESULT=" + result);
            String status = ready ? "Model ready — " + modelSpec.displayName
                    : "Model unavailable. Please choose another profile.";
            if (isDebugBuild()) {
                status = ready ? backend + " ready — " + modelSpec.displayName
                        : backend + " unavailable — " + result;
            }
            final boolean backendReady = ready;
            final String finalStatus = status;
            runOnUiThread(() -> {
                if (loadGeneration != modelLoadGeneration.get() || selectedModel != modelSpec) return;
                qnnModelReady = backendReady;
                ((TextView) findViewById(R.id.status_label)).setText(finalStatus);
                findViewById(R.id.btn_run).setEnabled(true);
                if (!backendReady) Toast.makeText(this, finalStatus, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void openMediaPicker(boolean image) {
        String type = image ? "image/*" : "video/*";
        int requestCode = image ? 2 : 1;
        if ("samsung_gallery".equals(mediaPicker)) {
            Intent gallery = new Intent(Intent.ACTION_PICK);
            gallery.setDataAndType(image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Video.Media.EXTERNAL_CONTENT_URI, type);
            gallery.setPackage("com.sec.android.gallery3d");
            if (gallery.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(gallery, requestCode);
                return;
            }
            Toast.makeText(this, "Samsung Gallery is unavailable; using system picker.", Toast.LENGTH_SHORT).show();
        }
        Intent system = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        system.addCategory(Intent.CATEGORY_OPENABLE);
        system.setType(type);
        startActivityForResult(system, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == 2) {
            videoPreview.stopPlayback();
            clearLivePreview();
            new Thread(() -> {
                try {
                    File f = new File(getCacheDir(), "input_orig_image");
                    copyUriToFile(uri, f);
                    currentImagePath = f.getAbsolutePath();
                    currentVideoPath = null;
                    imageMode = true;
                    Bitmap preview = BitmapFactory.decodeFile(currentImagePath);
                    runOnUiThread(() -> {
                        videoPreview.setVisibility(View.GONE);
                        livePreview.setVisibility(View.VISIBLE);
                        clearLivePreview();
                        currentLivePreview = preview;
                        livePreview.setImageBitmap(preview);
                        findViewById(R.id.video_status_text).setVisibility(View.GONE);
                        ((TextView) findViewById(R.id.status_label)).setText("Image ready: " + f.getName());
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Unable to import image: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
            return;
        }

        imageMode = false;
        currentImagePath = null;
        videoPreview.stopPlayback();
        clearLivePreview();
        findViewById(R.id.video_status_text).setVisibility(View.GONE);
        livePreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "input_orig.mp4");
                copyUriToFile(uri, f);
                currentVideoPath = f.getAbsolutePath();
                runOnUiThread(() -> {
                    videoPreview.setVideoPath(f.getAbsolutePath());
                    videoPreview.start();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Unable to import video: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void copyUriToFile(Uri uri, File target) throws java.io.IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new java.io.IOException("empty input stream");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
        }
    }

    private void clearLivePreview() {
        if (currentLivePreview != null && !currentLivePreview.isRecycled()) currentLivePreview.recycle();
        currentLivePreview = null;
        livePreview.setImageDrawable(null);
    }

    private Bitmap previewFromRgb(ByteBuffer rgb, int width, int height) {
        int maxSide = 720;
        float ratio = Math.min(1.0f, maxSide / (float) Math.max(width, height));
        int previewW = Math.max(1, Math.round(width * ratio));
        int previewH = Math.max(1, Math.round(height * ratio));
        int[] pixels = new int[previewW * previewH];
        for (int y = 0; y < previewH; y++) {
            int sourceY = Math.min(height - 1, Math.round(y / ratio));
            for (int x = 0; x < previewW; x++) {
                int sourceX = Math.min(width - 1, Math.round(x / ratio));
                int offset = (sourceY * width + sourceX) * 3;
                int r = rgb.get(offset) & 0xFF;
                int g = rgb.get(offset + 1) & 0xFF;
                int b = rgb.get(offset + 2) & 0xFF;
                pixels[y * previewW + x] = Color.rgb(r, g, b);
            }
        }
        Bitmap preview = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888);
        preview.setPixels(pixels, 0, previewW, 0, 0, previewW, previewH);
        return preview;
    }

    private void publishLivePreview(Bitmap next) {
        runOnUiThread(() -> {
            if (imageMode) {
                next.recycle();
                return;
            }
            if (currentLivePreview != null && !currentLivePreview.isRecycled()) currentLivePreview.recycle();
            currentLivePreview = next;
            livePreview.setImageBitmap(next);
            livePreview.setVisibility(View.VISIBLE);
        });
    }

    private void publishLiveRgbPreview(ByteBuffer rgb, int width, int height) {
        publishLivePreview(previewFromRgb(rgb, width, height));
    }

    private void publishLiveBitmapPreview(Bitmap source) {
        int maxSide = 720;
        float ratio = Math.min(1.0f, maxSide / (float) Math.max(source.getWidth(), source.getHeight()));
        Bitmap preview = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * ratio)),
                Math.max(1, Math.round(source.getHeight() * ratio)), true);
        publishLivePreview(preview);
    }

    private float getVideoFramerate(String path) {
        float frameRate = 30f;
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int numTracks = extractor.getTrackCount();
            for (int i = 0; i < numTracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    }
                    break;
                }
            }
            extractor.release();
        } catch (Exception e) {}
        return frameRate > 0 ? frameRate : 30f;
    }

    private int[] getVideoDimensions(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return new int[]{format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT)};
                }
            }
        } catch (Exception ignored) {
        } finally {
            extractor.release();
        }
        return new int[]{0, 0};
    }

    private float getVideoDurationSeconds(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return format.getLong(MediaFormat.KEY_DURATION) / 1_000_000f;
                }
            }
        } catch (Exception ignored) {
        } finally {
            extractor.release();
        }
        return 0f;
    }

    private boolean usesFastRawPipeline(String path) {
        int[] dims = getVideoDimensions(path);
        // The raw pipeline is dimension-agnostic; the old 16:9 gate forced square
        // videos through the slow JPEG/Bitmap legacy path.
        return dims[0] > 0 && dims[1] > 0;
    }

    private int readFully(InputStream input, byte[] buffer) throws java.io.IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private void showImageComparisonDialog(Bitmap before, Bitmap after, String modelName) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_image_comparison);
        dialog.setCanceledOnTouchOutside(true);

        SyncZoomImageView beforeView = dialog.findViewById(R.id.comparison_before);
        SyncZoomImageView afterView = dialog.findViewById(R.id.comparison_after);
        beforeView.setImageBitmap(before);
        afterView.setImageBitmap(after);
        beforeView.setPartner(afterView);
        afterView.setPartner(beforeView);
        ((TextView) dialog.findViewById(R.id.comparison_meta)).setText(
                before.getWidth() + "×" + before.getHeight() + " → "
                        + after.getWidth() + "×" + after.getHeight() + "  •  linked pan and zoom");

        dialog.findViewById(R.id.btn_comparison_close).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btn_comparison_reset).setOnClickListener(v -> beforeView.resetViewport());
        final AtomicBoolean saveInProgress = new AtomicBoolean(false);
        final AtomicBoolean comparisonDismissed = new AtomicBoolean(false);
        final Runnable recycleComparison = () -> {
            if (!before.isRecycled()) before.recycle();
            if (!after.isRecycled()) after.recycle();
        };
        View saveButton = dialog.findViewById(R.id.btn_comparison_save);
        saveButton.setOnClickListener(v -> {
            if (!saveInProgress.compareAndSet(false, true)) return;
            saveButton.setEnabled(false);
            new Thread(() -> {
                try {
                    saveImageToGallery(after);
                    runOnUiThread(() -> Toast.makeText(this, "Saved " + imageFormat + " image to Gallery", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                } finally {
                    runOnUiThread(() -> {
                        saveInProgress.set(false);
                        if (comparisonDismissed.get()) recycleComparison.run();
                        else saveButton.setEnabled(true);
                    });
                }
            }).start();
        });
        dialog.setOnDismissListener(v -> {
            comparisonDismissed.set(true);
            // The background writer owns the bitmaps until it finishes.
            if (!saveInProgress.get()) recycleComparison.run();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.42f);
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.84f));
        }
    }

    private void processImage(String path, String modelName) {
        new Thread(() -> {
            long start = System.currentTimeMillis();
            final AtomicBoolean progressActive = new AtomicBoolean(true);
            runOnUiThread(() -> {
                findViewById(R.id.progress_container).setVisibility(View.VISIBLE);
                LinearProgressIndicator progressBar = (LinearProgressIndicator) findViewById(R.id.progress_bar);
                progressBar.setProgressCompat(0, false);
                setProcessingAction(true);
                ((TextView) findViewById(R.id.status_label)).setText("Upscaling image...");
                startNativeProgressPolling(progressActive, (TextView) findViewById(R.id.status_label),
                        progressBar, start);
            });
            Bitmap input = null;
            Bitmap output = null;
            try {
                input = BitmapFactory.decodeFile(path);
                if (input == null) throw new IllegalStateException("Unable to decode image");
                int inputW = input.getWidth();
                int inputH = input.getHeight();
                int outputW = inputW * selectedModel.scale;
                int outputH = inputH * selectedModel.scale;
                int[] inputPixels = new int[inputW * inputH];
                int[] outputPixels = new int[outputW * outputH];
                ByteBuffer inputBuffer = ByteBuffer.allocateDirect(inputW * inputH * 3).order(ByteOrder.nativeOrder());
                ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputW * outputH * 3).order(ByteOrder.nativeOrder());
                bitmapToRgbBuffer(input, inputPixels, inputBuffer);
                String result = nativeUpscaleRgb(inputBuffer, inputW, inputH, outputBuffer);
                if (!result.startsWith("OK")) throw new IllegalStateException(result);
                output = Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888);
                rgbBufferToBitmap(outputBuffer, outputPixels, output, outputW, outputH);
                final Bitmap before = input;
                final Bitmap after = output;
                runOnUiThread(() -> {
                    findViewById(R.id.progress_container).setVisibility(View.GONE);
                    setProcessingAction(false);
                    ((TextView) findViewById(R.id.status_label)).setText("Image ready for comparison");
                    showImageComparisonDialog(before, after, modelName);
                });
                input = null;
                output = null;
                Log.i(TAG, String.format(Locale.US, "IMAGE_OUTPUT model=%s input=%dx%d output=%dx%d format=%s elapsed_ms=%d",
                        modelName, inputW, inputH, outputW, outputH, imageFormat, System.currentTimeMillis() - start));
            } catch (Exception e) {
                Log.e(TAG, "Image processing failed", e);
                if (output != null) output.recycle();
                runOnUiThread(() -> {
                    findViewById(R.id.progress_container).setVisibility(View.GONE);
                    setProcessingAction(false);
                    ((TextView) findViewById(R.id.status_label)).setText("Image processing failed.");
                    Toast.makeText(this, "Image processing failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                progressActive.set(false);
                if (input != null) input.recycle();
            }
        }).start();
    }

    private String saveImageToGallery(Bitmap bitmap) throws java.io.IOException {
        boolean jpg = "JPG".equals(imageFormat);
        String extension = jpg ? "jpg" : "png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Xyether_" + System.currentTimeMillis() + "." + extension);
        values.put(MediaStore.Images.Media.MIME_TYPE, jpg ? "image/jpeg" : "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Xyether");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new java.io.IOException("unable to create gallery item");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("unable to open gallery item");
            if (!bitmap.compress(jpg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                    jpg ? imageJpegQuality : 100, out)) {
                throw new java.io.IOException("bitmap compression failed");
            }
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            if (e instanceof java.io.IOException) throw (java.io.IOException) e;
            throw new java.io.IOException(e);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
        }
        return uri.toString();
    }

    private void processVideoFast(String path, String modelName, String dlssChoice) {
        final int modelScale = selectedModel.scale;
        new Thread(() -> {
            isCancelled = false;
            String cacheOutPath = getCacheDir().getAbsolutePath() + "/Xyether_Final.mp4";
            File oldFinalVideo = new File(cacheOutPath);
            if (oldFinalVideo.exists()) oldFinalVideo.delete();

            final float etaFps = getVideoFramerate(path);
            final int totalVideoFrames = Math.max(0, Math.round(getVideoDurationSeconds(path) * etaFps));
            final AtomicBoolean nativeProgressActive = new AtomicBoolean(true);
            final long progressStartMs = System.currentTimeMillis();
            runOnUiThread(() -> {
                findViewById(R.id.progress_container).setVisibility(View.VISIBLE);
                LinearProgressIndicator progressBar =
                        (LinearProgressIndicator) findViewById(R.id.progress_bar);
                progressBar.setProgressCompat(0, false);
                setProcessingAction(true);
                videoPreview.setVisibility(View.GONE);
                clearLivePreview();
                livePreview.setVisibility(View.VISIBLE);
                ((TextView) findViewById(R.id.status_label)).setText("Streaming frames...");
                startNativeProgressPolling(nativeProgressActive,
                        (TextView) findViewById(R.id.status_label), progressBar, progressStartMs);
            });

            // Fast path owns no JPEG frame directory: native RGB is streamed directly to the encoder.
            deleteDir(new File(getCacheDir(), "frames_out"));
            File rawInputPipe = new File(getCacheDir(), "frames_rgb.pipe");
            File rawOutputPipe = new File(getCacheDir(), "frames_upscaled_rgb.pipe");
            if (rawInputPipe.exists()) rawInputPipe.delete();
            if (rawOutputPipe.exists()) rawOutputPipe.delete();

            int[] sourceDims = getVideoDimensions(path);
            int sourceW = sourceDims[0];
            int sourceH = sourceDims[1];
            if (sourceW <= 0 || sourceH <= 0) {
                runOnUiThread(() -> ((TextView) findViewById(R.id.status_label)).setText("Unable to read video dimensions."));
                return;
            }

            float scale = 1.0f;
            if (dlssChoice.contains("80%")) scale = 0.8f;
            else if (dlssChoice.contains("60%")) scale = 0.6f;
            int processW = scale == 1.0f ? sourceW : Math.max(2, ((int) (sourceW * scale)) & ~1);
            int processH = scale == 1.0f ? sourceH : Math.max(2, ((int) (sourceH * scale)) & ~1);
            int frameBytes = processW * processH * 3;
            int outputW = processW * modelScale;
            int outputH = processH * modelScale;
            int outputFrameBytes = outputW * outputH * 3;
            float originalFps = etaFps;
            String scaleFilter = scale == 1.0f ? "" : String.format(Locale.US,
                    "-vf \"scale=trunc(iw*%.2f/2)*2:trunc(ih*%.2f/2)*2\" ", scale, scale);
            String rawDecodeCommand = "-y -noautorotate -i \"" + path + "\" " + scaleFilter
                    + "-an -f rawvideo -pix_fmt rgb24 \"" + rawInputPipe.getAbsolutePath() + "\"";
            String rawEncodeCommand = String.format(Locale.US,
                    "-y -f rawvideo -pixel_format rgb24 -video_size %dx%d -framerate %.6f -i \"%s\" -i \"%s\" "
                            + "-map 0:v:0 -map 1:a? " + videoEncodeArgs()
                            + " -g 48 -pix_fmt nv12 -c:a copy -shortest \"%s\"",
                    outputW, outputH, originalFps, rawOutputPipe.getAbsolutePath(), path, cacheOutPath);
            CountDownLatch encoderDone = new CountDownLatch(1);

            long startTime = System.currentTimeMillis();
            int frameIndex = 0;
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder());
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputFrameBytes).order(ByteOrder.nativeOrder());
            byte[] rawFrame = new byte[frameBytes];
            long rawReadNs = 0;
            long inputCopyNs = 0;
            long nativeNs = 0;
            long outputWriteNs = 0;
            long frameTotalNs = 0;
            Log.i(TAG, String.format(Locale.US,
                    "XY_PROFILE_DIRECT_START source=%dx%d process=%dx%d output=%dx%d rawBytes=%d scale=%.2f",
                    sourceW, sourceH, processW, processH, outputW, outputH, frameBytes, scale));

            try {
                Os.mkfifo(rawInputPipe.getAbsolutePath(), 0600);
                Os.mkfifo(rawOutputPipe.getAbsolutePath(), 0600);
                FFmpegKit.executeAsync(rawEncodeCommand, session -> {
                    Log.i(TAG, "Direct encoder finished with return code " + session.getReturnCode());
                    encoderDone.countDown();
                });
                FFmpegKit.executeAsync(rawDecodeCommand, session -> {
                    Log.i(TAG, "Raw frame producer finished with return code " + session.getReturnCode());
                });
                try (FileInputStream rawInput = new FileInputStream(rawInputPipe);
                     FileOutputStream rawOutput = new FileOutputStream(rawOutputPipe)) {
                    while (!isCancelled) {
                        final long frameStartNs = System.nanoTime();
                        final long rawReadStartNs = System.nanoTime();
                        int read = readFully(rawInput, rawFrame);
                        final long rawReadThisNs = System.nanoTime() - rawReadStartNs;
                        if (read == 0) break;
                        if (read != frameBytes) throw new IllegalStateException("Truncated raw video frame");

                        final long inputCopyStartNs = System.nanoTime();
                        inputBuffer.clear();
                        inputBuffer.put(rawFrame);
                        inputBuffer.flip();
                        final long inputCopyThisNs = System.nanoTime() - inputCopyStartNs;

                        outputBuffer.clear();
                        final long nativeStartNs = System.nanoTime();
                        String result = nativeUpscaleRgb(inputBuffer, processW, processH, outputBuffer);
                        final long nativeThisNs = System.nanoTime() - nativeStartNs;
                        if (!result.startsWith("OK")) throw new RuntimeException(result);

                        outputBuffer.rewind();
                        final long outputWriteStartNs = System.nanoTime();
                        while (outputBuffer.hasRemaining()) {
                            rawOutput.getChannel().write(outputBuffer);
                        }
                        final long outputWriteThisNs = System.nanoTime() - outputWriteStartNs;
                        final long frameThisNs = System.nanoTime() - frameStartNs;
                        rawReadNs += rawReadThisNs;
                        inputCopyNs += inputCopyThisNs;
                        nativeNs += nativeThisNs;
                        outputWriteNs += outputWriteThisNs;
                        frameTotalNs += frameThisNs;

                        frameIndex++;
                        if ((frameIndex & 7) == 0) publishLiveRgbPreview(outputBuffer, outputW, outputH);
                        if (frameIndex <= 3 || (frameIndex & 7) == 0) {
                            Log.i(TAG, String.format(Locale.US,
                                    "XY_PROFILE_DIRECT_FRAME index=%d raw_wait_ms=%.3f input_copy_ms=%.3f native_ms=%.3f encoder_write_ms=%.3f total_ms=%.3f",
                                    frameIndex, rawReadThisNs / 1_000_000.0, inputCopyThisNs / 1_000_000.0,
                                    nativeThisNs / 1_000_000.0, outputWriteThisNs / 1_000_000.0,
                                    frameThisNs / 1_000_000.0));
                        }
                        if ((frameIndex & 7) == 0) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            float fps = frameIndex / (elapsed / 1000f);
                            boolean haveEta = totalVideoFrames > frameIndex && fps > 0;
                            String etaText = haveEta
                                    ? formatEta((long) ((totalVideoFrames - frameIndex) / fps)) : "—";
                            String status = String.format(Locale.US, "Upscaling: %d/%d | FPS: %.1f | ETA: %s",
                                    frameIndex, totalVideoFrames > 0 ? totalVideoFrames : frameIndex,
                                    fps, etaText);
                            runOnUiThread(() -> {
                                if (!isCancelled) ((TextView) findViewById(R.id.status_label)).setText(status);
                            });
                        }
                    }
                }
                if (!encoderDone.await(120, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for the hardware video encoder");
                }
            } catch (Exception e) {
                Log.e(TAG, "Direct raw-frame pipeline failed", e);
                runOnUiThread(() -> {
                    setProcessingAction(false);
                    Toast.makeText(this, "Direct frame pipeline failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    ((TextView) findViewById(R.id.status_label)).setText("Direct frame pipeline failed.");
                });
                return;
            } finally {
                nativeProgressActive.set(false);
                rawInputPipe.delete();
                rawOutputPipe.delete();
            }

            if (frameIndex > 0) {
                Log.i(TAG, String.format(Locale.US,
                        "XY_PROFILE_DIRECT_SUMMARY frames=%d raw_wait_ms=%.3f input_copy_ms=%.3f native_ms=%.3f encoder_write_ms=%.3f total_ms=%.3f fps=%.3f",
                        frameIndex, rawReadNs / 1_000_000.0, inputCopyNs / 1_000_000.0,
                        nativeNs / 1_000_000.0, outputWriteNs / 1_000_000.0,
                        frameTotalNs / 1_000_000.0,
                        frameIndex * 1_000_000_000.0 / frameTotalNs));
            }
            if (frameIndex == 0) {
                runOnUiThread(() -> {
                    setProcessingAction(false);
                    ((TextView) findViewById(R.id.status_label)).setText("No decoded video frames.");
                });
                return;
            }
            runOnUiThread(() -> {
                ((TextView) findViewById(R.id.status_label)).setText("Finalizing video...");
                setProcessingAction(false);
            });

            File finalVideo = new File(cacheOutPath);
            if (!finalVideo.exists() || finalVideo.length() == 0) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to stitch final video", Toast.LENGTH_LONG).show());
                return;
            }
            showSuccessPopup(finalVideo, modelName, System.currentTimeMillis() - startTime);
        }).start();
    }

    private void processVideoLegacy(String path, String modelName, String dlssChoice) {
        final int modelScale = selectedModel.scale;
        new Thread(() -> {
            isCancelled = false;

            String cacheOutPath = getCacheDir().getAbsolutePath() + "/Xyether_Final.mp4";
            File oldFinalVideo = new File(cacheOutPath);
            if (oldFinalVideo.exists()) {
                oldFinalVideo.delete();
            }

            runOnUiThread(() -> {
                findViewById(R.id.progress_container).setVisibility(View.VISIBLE);
                setProcessingAction(true);
                videoPreview.setVisibility(View.GONE);
                clearLivePreview();
                livePreview.setVisibility(View.VISIBLE);
                ((TextView) findViewById(R.id.status_label)).setText("Extracting Frames...");
            });

            File inDir = new File(getCacheDir(), "frames_in");
            File outDir = new File(getCacheDir(), "frames_out");
            deleteDir(inDir);
            deleteDir(outDir);
            inDir.mkdirs();
            outDir.mkdirs();

            String scaleFilter = "";
            if (dlssChoice.contains("80%")) scaleFilter = "-vf \"scale=iw*0.8:-2\" ";
            else if (dlssChoice.contains("60%")) scaleFilter = "-vf \"scale=iw*0.6:-2\" ";

            FFmpegKit.execute("-y -i \"" + path + "\" " + scaleFilter + "-q:v 2 \"" + inDir.getAbsolutePath() + "/%08d.jpg\"");

            File[] frames = inDir.listFiles();
            if (frames == null || frames.length == 0) {
                runOnUiThread(() -> {
                    setProcessingAction(false);
                    ((TextView) findViewById(R.id.status_label)).setText("No video frames could be extracted.");
                });
                return;
            }
            Arrays.sort(frames);

            long startTime = System.currentTimeMillis();
            Bitmap reusableInput = null;
            Bitmap reusableOutput = null;
            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decodeOptions.inMutable = true;
            int[] inputPixels = null;
            int[] outputPixels = null;
            ByteBuffer inputBuffer = null;
            ByteBuffer outputBuffer = null;

            // --- FRAME PROCESSING LOOP ---
            for (int i = 0; i < frames.length; i++) {
                if (isCancelled) break;

                File inFrame = frames[i];
                File outFrame = new File(outDir, inFrame.getName());

                try {
                    if (reusableInput != null) decodeOptions.inBitmap = reusableInput;
                    Bitmap inputBitmap;
                    try {
                        inputBitmap = BitmapFactory.decodeFile(inFrame.getAbsolutePath(), decodeOptions);
                    } catch (IllegalArgumentException incompatibleReuse) {
                        decodeOptions.inBitmap = null;
                        inputBitmap = BitmapFactory.decodeFile(inFrame.getAbsolutePath(), decodeOptions);
                    }
                    if (inputBitmap == null) throw new IllegalStateException("Unable to decode frame");
                    if (reusableInput != inputBitmap && reusableInput != null) reusableInput.recycle();
                    reusableInput = inputBitmap;

                    int origW = inputBitmap.getWidth();
                    int origH = inputBitmap.getHeight();
                    int outWidth = origW * modelScale;
                    int outHeight = origH * modelScale;
                    if (inputPixels == null || inputPixels.length != origW * origH) {
                        inputPixels = new int[origW * origH];
                        inputBuffer = ByteBuffer.allocateDirect(origW * origH * 3).order(ByteOrder.nativeOrder());
                    }
                    if (outputPixels == null || outputPixels.length != outWidth * outHeight) {
                        outputPixels = new int[outWidth * outHeight];
                        outputBuffer = ByteBuffer.allocateDirect(outWidth * outHeight * 3).order(ByteOrder.nativeOrder());
                        if (reusableOutput != null) reusableOutput.recycle();
                        reusableOutput = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
                    }

                    bitmapToRgbBuffer(inputBitmap, inputPixels, inputBuffer);
                    String result = nativeUpscaleRgb(inputBuffer, origW, origH, outputBuffer);
                    if (!result.startsWith("OK")) throw new RuntimeException(result);

                    rgbBufferToBitmap(outputBuffer, outputPixels, reusableOutput, outWidth, outHeight);
                    if ((i & 7) == 0) publishLiveBitmapPreview(reusableOutput);
                    try (FileOutputStream outStream = new FileOutputStream(outFrame)) {
                        reusableOutput.compress(Bitmap.CompressFormat.JPEG, 90, outStream);
                    }

                    int prog = (int) (((i + 1) / (float) frames.length) * 100);
                    int finalI = i;
                    long elapsed = System.currentTimeMillis() - startTime;
                    float fps = (finalI + 1) / (elapsed / 1000f);
                    int remainingFrames = frames.length - (finalI + 1);
                    int etaSeconds = (int) (fps > 0 ? remainingFrames / fps : 0);
                    String cleanStatus = String.format(Locale.US, "Upscaling: %d/%d | FPS: %.1f | ETA: %ds", (finalI + 1), frames.length, fps, etaSeconds);

                    if ((i & 7) == 0 || i + 1 == frames.length) {
                        runOnUiThread(() -> {
                            ((LinearProgressIndicator) findViewById(R.id.progress_bar)).setProgressCompat(prog, true);
                            if (!isCancelled) ((TextView) findViewById(R.id.status_label)).setText(cleanStatus);
                        });
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Frame processing failed at index " + i, e);
                    if (reusableInput != null) reusableInput.recycle();
                    if (reusableOutput != null) reusableOutput.recycle();
                    final int failedFrame = i + 1;
                    runOnUiThread(() -> {
                        setProcessingAction(false);
                        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        ((TextView) findViewById(R.id.status_label)).setText("Video frame failed: " + detail);
                        Toast.makeText(this, "Video stopped at frame " + failedFrame + ": " + detail, Toast.LENGTH_LONG).show();
                    });
                    return;
                }
            }
            if (reusableInput != null) reusableInput.recycle();
            if (reusableOutput != null) reusableOutput.recycle();

            runOnUiThread(() -> {
                ((TextView) findViewById(R.id.status_label)).setText("Stitching Video...");
                setProcessingAction(false);
            });

            float originalFps = getVideoFramerate(path);
            String fpsStr = String.format(Locale.US, "%.2f", originalFps);

            String ffmpegCmd = String.format(Locale.US,
                    "-framerate %s -i \"%s/%%08d.jpg\" -i \"%s\" -map 0:v -map 1:a? -vf \"pad=ceil(iw/2)*2:ceil(ih/2)*2\" "
                            + videoEncodeArgs() + " -pix_fmt nv12 -c:a copy -shortest -y \"%s\"",
                    fpsStr, outDir.getAbsolutePath(), path, cacheOutPath);

            FFmpegKit.execute(ffmpegCmd);

            File finalVideo = new File(cacheOutPath);
            if (!finalVideo.exists() || finalVideo.length() == 0) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to stitch final video", Toast.LENGTH_LONG).show());
                return;
            }

            long timeTaken = System.currentTimeMillis() - startTime;
            showSuccessPopup(finalVideo, modelName, timeTaken);

        }).start();
    }

    private void bitmapToRgbBuffer(Bitmap bitmap, int[] pixels, ByteBuffer rgb) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        rgb.clear();
        for (int pixel : pixels) {
            rgb.put((byte) ((pixel >> 16) & 0xFF));
            rgb.put((byte) ((pixel >> 8) & 0xFF));
            rgb.put((byte) (pixel & 0xFF));
        }
        rgb.flip();
    }

    private void rgbBufferToBitmap(ByteBuffer rgb, int[] pixels, Bitmap bitmap, int w, int h) {
        rgb.rewind();
        for (int i = 0; i < pixels.length; i++) {
            int r = rgb.get() & 0xFF;
            int g = rgb.get() & 0xFF;
            int b = rgb.get() & 0xFF;
            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private void showSuccessPopup(File finalVideo, String modelName, long timeTakenMs) {
        runOnUiThread(() -> {
            findViewById(R.id.progress_container).setVisibility(View.GONE);

            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.dialog_success);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            VideoView popupVideo = dialog.findViewById(R.id.popup_video_view);
            popupVideo.setVideoPath(finalVideo.getAbsolutePath());
            popupVideo.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
            });
            popupVideo.start();

            long seconds = timeTakenMs / 1000;
            TextView subtitle = dialog.findViewById(R.id.popup_subtitle);
            String saveStatus = isCancelled ? "(Aborted Early)\nVideo successfully stitched!" : "Your video is fully upscaled and ready.";
            subtitle.setText("Model: " + modelName + " (" + seconds + "s)\n" + saveStatus);

            dialog.findViewById(R.id.btn_save_device).setOnClickListener(v -> {
                saveToGallery(finalVideo);
                dialog.dismiss();
            });

            dialog.findViewById(R.id.btn_close_popup).setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        });
    }

    private void saveToGallery(File finalVideo) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "Xyether_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Xyether");

            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream out = getContentResolver().openOutputStream(uri);
                InputStream in = new FileInputStream(finalVideo);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                in.close();
                out.close();
                Toast.makeText(this, "Saved to Gallery! \uD83C\uDFAC", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {}
    }

    private void deleteDir(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File s : files) deleteDir(s);
        }
        f.delete();
    }

    @Override
    protected void onDestroy() {
        modelLoadGeneration.incrementAndGet();
        modelLoader.shutdownNow();
        nativeReleaseModel();
        super.onDestroy();
    }

    private void requirePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
    }
}


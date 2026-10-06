# Xyether Upscaler

Upscale images and videos to 2× their original resolution, directly on your Android phone. No uploads or cloud processing.

Built for **Snapdragon 8 Gen 1 and newer**, with eight profiles for anime and real-world footage. Snapdragon devices use Qualcomm’s on-device accelerator. Other ARM64 devices can try the MNN GPU path when their OpenCL or Vulkan driver supports it.

## Compatibility and speed

- Android 8.0 or newer, ARM64 only.
- Snapdragon 8 Gen 1 and newer is the performance target, not a guarantee that every device has been tested.
- Other chips require a compatible GPU driver. There is no CPU fallback, so this is **not a universal Android app**.
- Reported video upscaling speed: **6–25 FPS on Snapdragon 8 Gen 3**, with the higher speeds reached using Speed profiles. Performance varies with the selected model, video resolution and device.
- **Speed models are in beta and may cause color changes. They are not recommended; use Quality or Balanced profiles instead.**

## What it does

- Image upscaling with a linked before/after comparison.
- Video upscaling with hardware H.264/H.265 encoding and Gallery export.
- Anime sharp/soft profiles in Quality, Balanced and Speed variants, plus an IRL Quality beta.
- All eight models are included; there is no model download step.

## Release status

The app is in **beta** and has room for improvement. Download the APK from [Releases](https://github.com/XYETHER/XyetherUpscaler/releases). Feedback and device reports are welcome.

## Build

See [BUILDING.md](BUILDING.md) for the toolchain and native dependency setup. You need JDK17, Android SDK35, NDK27, CMake, Python and Git. Qualcomm SDK components are obtained separately under Qualcomm’s license.

```sh
python tools/prepare_native.py --sdk "PATH_TO_QAIRT_SDK" --appbuilder "PATH_TO_LIBAPPBUILDER"
python tools/verify_release.py
./gradlew :app:assembleRelease :app:lintRelease
```

On Windows, use `gradlew.bat`.

## License

App code and Xyether model weights: [Apache-2.0](LICENSE). Third-party components keep their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The Qualcomm runtime is proprietary, so the complete APK is not entirely open source. The code license does not automatically cover artwork.

[Privacy](PRIVACY.md) · [Security](SECURITY.md)

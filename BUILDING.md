# Build and reproduce

## Toolchain

JDK 17; Android SDK platform 35/build-tools; Android NDK 27.0.12077973; CMake 3.22.1; Python 3; Git. This is a GitHub APK distribution setup, not a claim of Play API36 readiness.

Set `JAVA_HOME` and `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or use Android Studio's local SDK configuration. Do not commit local.properties. Long Windows paths may require a short checkout path and long-path Git support.

## Native dependencies

Read their licenses first. Obtain QAIRT 2.49.0.260730 from Qualcomm and the matched ARM64 AppBuilder library. tools/native-dependencies.json records exact paths, sizes and SHA256 hashes. No credentials or SDK download bypass is included.

```bash
python tools/prepare_native.py --sdk "YOUR_SDK_ROOT" --appbuilder "YOUR_LIBAPPBUILDER_SO"
```

This fetches the pinned MNN commit and restores only hash-matched native files. An optional `--mnn-source "CLEAN_PINNED_CHECKOUT"` avoids downloading MNN, after verifying its commit and clean tracked working tree. Dependencies are gitignored. Run from a fresh checkout for reproducible validation; do not use an edited unverified MNN tree.

## Checks and build

```bash
python tools/verify_release.py
python tools/test_verify_release.py
./gradlew :app:assembleRelease :app:lintRelease
python tools/verify_release.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Windows: `gradlew.bat :app:assembleRelease :app:lintRelease`.

Unit/instrumentation placeholders from Android Studio were intentionally not presented as a product test suite. Native contract checks and APK inspection are focused checks, not device inference tests.

## Signing

Unsigned output is not installable as a production release. Sign with your own key using Android build-tools zipalign and apksigner. The publisher's first dedicated key is private and never included in this repository. Use the same key for every update of the same application ID. Fork authors must use their own key and should change the applicationId to prevent confusion. Existing debug-key installations cannot update in place to a differently signed public APK.

See the private publisher instructions outside the repository for local release signing. Do not upload private signing configurations, keys or passwords. Changes to LGPL dependencies must remain rebuildable/relinkable; no signature restriction is added to this source.

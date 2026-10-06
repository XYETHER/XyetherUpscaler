# Release status

The source and signed release candidate were reviewed on2026-10-06. The APK is not being published yet.

## What was checked

The supplied build report records successful release build and lint, a dedicated release signature, and four focused verifier tests. The new review independently checks the eight-profile asset manifest, reruns the verifier tests, verifies APK checksums/signature, and reads the active inference/video paths. It does not repeat device inference or establish universal compatibility.

## Remaining work

- **Video cancellation:** blocking FIFO opens/reads/writes can leave a job stuck; Abort does not unblock them. Track and cancel FFmpeg sessions, use bounded I/O, and test startup failures and stalls.
- **Video success:** check both FFmpeg return codes and finalized output. A nonempty partial file is currently enough to trigger the normal success popup.
- **Activity teardown:** cancel active processing, suppress callbacks into a destroyed Activity, use per-job temporary paths, and release native state away from the main thread.
- **FFmpeg distribution:** establish and provide corresponding native source/build materials for the exact6.1.4 binary and its enabled dependencies before distributing the APK.
- **Device checks:** exercise every profile, import/save, Abort, Activity recreation and low-memory behavior on the final signed APK. No new device testing was performed in this review.

The comparison-dialog bitmap ownership fix is present in the reviewed source, but still needs device regression testing. Qualcomm runtime distribution remains subject to its vendor license. Artwork ownership/redistribution permission has not been documented in this bundle.

The app targets Snapdragon8Gen1 and newer. Its ARM64 MNN path requires OpenCL/Vulkan GPU support and deliberately rejects CPU substitution. Do not describe it as universal or advertise6–11FPS as a cross-device guarantee.

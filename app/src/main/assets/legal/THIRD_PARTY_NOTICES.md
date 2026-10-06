# Third-party notices and scope

- **MNN**: Alibaba MNN, Apache-2.0, clean commit `4d40bdbd7efb73a58383bb5da867a6f41eb1776e`. Restored by tools/prepare_native.py. Upstream embedded third-party notices remain in its source tree; they are not superseded by Apache-2.0.
- **QAI AppBuilder**: Qualcomm Innovation Center, BSD-3-Clause; upstream https://github.com/quic/ai-engine-direct-helper, v2.49.0. Headers and matched binary are dependencies. Full BSD notice is in LICENSES/AppBuilder-BSD-3-Clause.txt. No claim is made that the local prebuilt was independently rebuilt from this tag.
- **QAIRT/QNN 2.49.0.260730**: proprietary Qualcomm AI Stack license. Section 1 permits object-code distribution incorporated in an application, not standalone SDK redistribution. Obtain the SDK independently for builds. SDK license and provided notices accompany the release; the source archive omits its standalone shared libraries. Comply with the license, including export restrictions.
- **FFmpegKit full 6.1.4**: `com.mrljdx:ffmpeg-kit-full:6.1.4`, POM declares LGPL-3.0. Native ARM64 FFmpeg configuration has `--enable-version3`, does not have `--enable-gpl` or `--enable-nonfree`, and enables many third-party libraries. Preserved AAR license files are in LICENSES/FFmpegKit-*.txt. Their presence is not proof every listed library is enabled. Upstream https://github.com/mrljdx/ffmpeg-kit.
- **AndroidX / Material / Gradle wrapper**: their upstream licenses apply (principally Apache-2.0). AndroidX graphics-path 1.0.1 is restored from its Google Maven artifact with a hash check. Gradle wrapper is unmodified project infrastructure.
- **Model weights**: Xyether states all eight supplied weights were made by Xyether; Xyether offers those weights under Apache-2.0. Architecture/training dependency provenance is separate. No datasets are distributed.
- **Artwork**: no provenance/license document was supplied. Do not assume third-party artwork is public domain or covered by the code license. Publisher must confirm ownership/permission before public distribution.

## Outstanding compliance gate

A Java sources JAR and an upstream link alone do not establish full LGPL native corresponding-source compliance. The exact native FFmpegKit/FFmpeg/external-library source versions and rebuild materials for Maven 6.1.4 must be established and provided by a compliant mechanism before distributing the APK. The source package and app license do not waive that requirement. See release readiness report.

This document records findings; it is not legal advice or a legal certification.

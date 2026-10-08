# Third-party components

Mirror's original source is MIT licensed. Minecraft is not included in the mod
and remains subject to Mojang/Microsoft's own terms. This is not an official
Minecraft product and is not approved by or associated with Mojang or Microsoft.

The release includes the Fabric API aggregate jar, with its nested modules, and
the Java libraries needed by the isolated source engine. Their original license
files remain inside those jars. Native media jars are included for offline use;
browser native jars on Linux/macOS are downloaded from pinned Maven Central URLs.
Installed Google Chrome is used on Windows and is not redistributed by Mirror.

| Component | Version | Upstream / license |
|---|---|---|
| Fabric API | 0.141.6+1.21.11 | [FabricMC](https://github.com/FabricMC/fabric), Apache-2.0 |
| Polymer Core / Common / Networking / Registry Sync Manipulator | 0.15.2+1.21.11 | [Polymer](https://github.com/Patbox/polymer/tree/0.15.2%2B1.21.11), LGPL-3.0; corresponding source in the source release |
| Packet Tweaker | 0.6.0+1.21.10 | [NucleoidMC](https://github.com/NucleoidMC/packet-tweaker/tree/v0.6.0%2B1.21.10), LGPL-3.0; corresponding source in the source release |
| JCEF Maven | 152.0.6 | [jcefmaven](https://github.com/jcefmaven/jcefmaven), Apache-2.0 wrapper; JCEF/CEF/Chromium BSD-style notices |
| JavaCV / JavaCPP | 1.5.14 | [Bytedeco](https://github.com/bytedeco/javacv), Apache-2.0 |
| FFmpeg preset / native bundles | 8.1.2-1.5.14 | [JavaCPP presets](https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg); LGPL-3.0-or-later native configuration, with corresponding source package |
| JNA / JNA Platform | 5.18.1 | [JNA](https://github.com/java-native-access/jna), Apache-2.0 / LGPL-2.1-or-later |
| PDFBox / FontBox | 3.0.6 | [Apache PDFBox](https://pdfbox.apache.org/), Apache-2.0 |
| Gson | 2.11.0 | [Gson](https://github.com/google/gson), Apache-2.0 |
| Apache Commons | versions pinned by the dependency graph | Apache-2.0, original notices in jars |
| JOGL / GlueGen | v2.4.0 | JogAmp, original license files in jars/native packages; Mirror's CPU browser renderer does not use JOGL |

The actual native runtime reports **LGPL version 3 or later**; its build has
`--enable-version3`, without `--enable-gpl` or `--enable-nonfree`. Mirror dynamically
loads the original FFmpeg libraries. Native blobs are not covered by Mirror's MIT
license. This software uses libraries from the FFmpeg project under the LGPLv3.
The measured configure string is in `licenses/measured-ffmpeg-build.txt`.

The [source release](https://github.com/hurtorius/Mirror/releases/tag/v1.6.1) includes
`mirror-media-sources-1.5.14.zip`, containing the
upstream FFmpeg source, enabled codec/library sources, tagged build recipe,
required patch, license texts and a SHA-256 source manifest. Keep it available with
the binary when redistributing. Original notices inside each source archive and
Java jar remain in place. The source is provided to allow rebuilding/replacing the
libraries; Mirror does not prohibit reverse engineering those LGPL components.
See [FFmpeg's license information](https://ffmpeg.org/legal.html).

No Google Chrome user profile, Microsoft Edge user profile, personal account,
Minecraft client/server binary, commercial codec key, or DRM bypass is included.

The merged screen mesh, preview and projector code is copyright 2026 Mirror
contributors under MIT. Its complete notice is included in the mod at
`META-INF/licenses/MERGED-SOURCE-LICENSE.txt`. The MIT notice for those components is also available in `docs/MERGED-SOURCE-LICENSE.txt`.

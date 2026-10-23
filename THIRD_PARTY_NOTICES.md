# 第三方依赖与许可

本项目自有源码按根目录 MIT License 发布。下载清单位于 `tools/dependencies.json`，包括原始 URL、固定版本及下载/解包文件 SHA256；没有把个人设备提取物当作依赖。

| 项目 | 来源 | 许可 / 分发方式 |
|---|---|---|
| sherpa-onnx 1.13.8 | https://github.com/k2-fsa/sherpa-onnx | Apache-2.0；保留 `system/speech-http/licenses/sherpa-onnx-LICENSE`；JNI 运行依赖由本机下载 |
| sherpa KWS 模型 zh-en-3M | 同仓库 `kws-models` Release / 上游 KWS 文档 | 从官方模型包下载，不将个人录音或训练样本作为模型分发；模型使用受其上游条款约束 |
| WebRTC Android SDK 125.6422.07 | https://github.com/webrtc-sdk/android | SDK MIT、WebRTC BSD-3-Clause；许可在 `system/voice-client/licenses/`，APK 同时携带；WebRTC 包含上游第三方组件 |
| ONNX Runtime | sherpa 官方 Android 运行包 | MIT；官方 JNI 包含对应运行库，下载后使用者保留上游许可 |
| Frida 16.7.19 | https://github.com/frida/frida | 上游组件按 LGPL 等许可发布；这里只提供官方下载地址与校验，不把其二进制捆绑到 Release 工具包 |
| Android platform 27 | Google Android repository | 仅作编译依赖；使用须遵守 Android SDK 许可，不随工具包分发 |
| R8 8.3.37 | Google Maven | BSD-3-Clause；仅作编译依赖，不随工具包分发 |
| aiohttp 3.13.3 | https://github.com/aio-libs/aiohttp | Apache-2.0；由 pip 安装 |
| websockets 16.0 | https://github.com/python-websockets/websockets | BSD-3-Clause；由 pip 安装 |

源码中的 sherpa Java 类型是与官方 JNI 匹配的最小接口适配。未打包原厂小度 APK、固件或商业音乐/视频 APK；运行时调用的厂商接口属于原设备系统，兼容性取决于实际版本。

Codex、ChatGPT、小度及相关商标属于各自权利人，本项目为个人研究与设备改造实践，不代表官方产品或官方稳定接口承诺。

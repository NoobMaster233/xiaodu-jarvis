# 从源码构建与打包

## Linux 构建环境

Python 3.10+、JDK 17，运行loader回归需要Node.js 18+；Debian/Ubuntu 可安装：

```sh
sudo apt-get install python3 python3-venv openjdk-17-jdk aapt zipalign apksigner adb
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

无需 Android Studio 或 Gradle。aapt/zipalign/apksigner 使用系统软件包；Android 平台 JAR、R8、WebRTC 等使用锁定文件和 SHA256。

## 下载固定依赖

```sh
python3 tools/prepare.py
```

[tools/dependencies.json](../tools/dependencies.json)锁定每份下载包及提取文件的 SHA256。下载后只提取清单指定的普通文件，不把归档内容任意解压到仓库。网络不可用时，可用 `--cache-from 另一个同布局缓存根目录` 复用**同一 SHA256**的文件；这不会接受不同版本依赖。

| 依赖 | 版本 / 用途 |
|---|---|
| Android platform | API 27 / Java 编译 |
| R8 | 8.3.37 / D8 生成 dex |
| WebRTC Android SDK | 125.6422.07 / ARMv7 媒体 |
| sherpa-onnx | 1.13.8 / JNI KWS |
| KWS 模型 | zh-en-3M-2025-12-20 / INT8 encoder、joiner，FP32 decoder |
| frida-inject | 16.7.19 android-arm / 设备内一次性加载 |

约定 `runtime` 下载只包含 Frida、sherpa JNI 和模型；Release 使用者不重新编译 APK，可运行 `python3 tools/prepare.py --group runtime`。

## 编译

```sh
python3 scripts/build_speech_http.py
python3 scripts/build_voice_client.py
python3 scripts/build_screen_care.py
python3 tools/package_module.py
```

输出位置：

| 输出 | 内容 |
|---|---|
| `artifacts/speech-http/speech-http.jar` | 设备内加载的 dex |
| `artifacts/voice-client/xiaodu-voice-1.7.apk` | 原生语音客户端 |
| `artifacts/screen-care/screen-care-1.3.apk` | 可选时钟 |
| `artifacts/xiaodu-speech-module.zip` | 从已校验依赖组装的模块目录包，供首次安装辅助工具使用 |
| 各目录 `build-proof.json` | 公开源码相对路径、依赖及输出 SHA256 |

模块 ZIP 是目录负载包，由 `tools/device.py` 按文档解包；**未封装 Magisk App 的通用 recovery 安装器**，不要把它当作可在任意设备上刷入的固件。

两个 APK 首次构建时分别生成独立签名密钥，留在忽略的 `private/`，权限为 700/600。保管自己的签名密钥用于后续升级，勿提交或发布。Release 中只包含已签名 APK 的公开签名，不包含私钥。自己编译与 Release 的签名不同，不能静默覆盖；安装失败时先明确决定是否卸载，不自动清数据。

关键词由 `examples/keywords.txt` 复制到模块中，仅在可选local模式使用已测试的“真维斯”；默认factory模式仍喊“小度小度”。更换词需要正确的音节/token 序列和新的真人正负例验收，不能只改 `@` 后的显示名。

## 离线检查

```sh
.venv/bin/python -m unittest discover -s scripts/tests -p 'test_*.py'
python3 tools/check_public.py
python3 -m compileall -q system/codex-bridge tools scripts client
```

测试只运行临时文件、模拟 RPC、纯 JVM 状态机及隔离子进程，不接真实 Codex 对话、设备或麦克风。CI 运行同类检查；完整 APK 构建与 SHA256 在发布前本地验证。

## 生成自己的工具包

```sh
python3 tools/release.py
```

发布前会强制核对源码文件集合、内容、构建脚本与产物的 SHA256；修改过源码却没重建会拒绝打包。版本号读取根目录 `VERSION`。

只打包明确允许的公开源码、文档、Skill、两个 APK 和服务 JAR，不包含缓存、模型、Frida 二进制、签名私钥、个人配置或状态。第三方运行依赖仍在使用者本机下载校验。输出在忽略的 `artifacts/release/`，同时生成 `SHA256SUMS`。

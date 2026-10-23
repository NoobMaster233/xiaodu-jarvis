# 快速开始

## 准备条件

- 小度智能屏 **1S / XDH-29-B1**，Android API 27、ARMv7，已 root、有 Magisk 与其 BusyBox；原厂 DCS 服务仍可用且已激活。
- Linux 管理机和小度在互通的可信局域网，双方能联网。给设备和管理机保留稳定的 DHCP 地址更方便。
- Python 3.10+、ADB；若从源码编译，还需 JDK 17、aapt、zipalign、apksigner。
- 管理机上的 Codex 已登录，已有要接入的主对话和正在工作的 app-server。不要为了接入而复制别人的 `auth.json` 或登录令牌。

本项目对接 Linux 上**现有 Codex app-server 的 Unix WebSocket**。自动发现路径为 `/tmp/codex-daemon-当前UID/`，校验监听者确实是同用户的 Codex app-server；不是连接 daemon-management socket。如果你的部署只提供 stdio、不提供这种套接字，需要自行适配传输，当前工具不会假装支持。作者验证过的相邻 Codex 版本包括 0.163/0.164；不保证任意版本或账号都开放实验性实时语音。

## 1. 下载或构建

推荐从 [Release](https://github.com/NoobMaster233/xiaodu-jarvis/releases/tag/v0.1.0)下载 `xiaodu-jarvis-v0.1.0.zip` 和 `SHA256SUMS`，校验后解压。工具包已经带本项目的 APK 和服务 JAR，第三方运行依赖按固定 SHA256 下载。

```sh
sha256sum -c SHA256SUMS --ignore-missing
unzip xiaodu-jarvis-v0.1.0.zip
cd xiaodu-jarvis
python3 tools/prepare.py --group runtime
```

源码使用者先按 [构建文档](build.md)生成 APK 和 JAR。不要拿日用设备上的私有旧 APK 代替公开版，旧包可能含旧地址且签名不同。

## 2. 配置管理机

以下以仓库放在 `~/xiaodu-jarvis` 为例。发行包也可以放到同一目录。

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
mkdir -p ~/.config/xiaodu-jarvis
cp config.example.json ~/.config/xiaodu-jarvis/config.json
chmod 600 ~/.config/xiaodu-jarvis/config.json
```

用编辑器修改该 JSON：

| 字段 | 填什么 |
|---|---|
| `main_thread_id` | 你自己的已有 Codex 主对话 ID，来自所用 Codex 客户端或对话记录 |
| `device_url` | 小度局域网地址，例如 `http://设备IP:8765` |
| `voice` | 默认 `juniper`，实验性协议支持情况以所用 Codex 版本为准 |
| `listen_host` / `listen_port` | 桥接监听地址，默认 `0.0.0.0:8766` |
| `codex_socket` | 一般留 `null` 自动发现；多个 app-server 时填目标 Unix socket 的绝对路径 |
| 三个模型相关字段 | 留 `null` 保留自己主对话的现有设置 |

可用 `XIAODU_CONFIG` 指定其他配置文件。桥接状态写在 `~/.local/state/xiaodu-jarvis`，可用配置中的 `state_dir` 改路径。这里没有新增局域网密码、token 或配对流程。

先在前台运行，便于看是否找到 Codex：

```sh
.venv/bin/python system/codex-bridge/bridge.py
```

另一个终端检查 `curl http://127.0.0.1:8766/health`，应为 `ready=true`；`connection_error` 指出套接字问题。小度未安装前，设备状态请求失败是正常的，不能算实时链路已经通过。

确认后可安装用户级 systemd 服务；示例文件假定目录为 `~/xiaodu-jarvis`，路径不同时先修改其中两处路径：

```sh
mkdir -p ~/.config/systemd/user
cp system/codex-bridge/xiaodu-codex-bridge.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now xiaodu-codex-bridge
```

需要无登录会话持续运行时，由系统管理员为该用户启用 linger。不要同时运行前台桥接与 systemd 两个实例。

## 3. 首次安装设备组件

ADB 可通过 USB 或你已经配置好的无线 ADB 连接。先用 `adb devices` 找到目标 serial，命令必须明确指定，工具不自动猜家里的设备。

```sh
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766
```

上面只显示计划，不碰设备。确认型号和目标地址后，加 `--apply` 才执行：

```sh
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766 --apply
```

脚本检查机型/API/ABI/root/Magisk，校验 APK，安装语音和时钟，授予必要权限，将桥接地址存到语音应用私有目录，组装并启动设备内模块。不会发起测试通话、录音或整机重启。若存在同名模块会拒绝覆盖；签名冲突也不会自动卸载旧应用。它是**首次安装辅助工具**，不是跨机型刷机或通用升级器。

模块依赖设备允许 root 进程内加载。原有实机环境为 SELinux Permissive；Enforcing 环境没有验证，工具不会自动修改策略。若加载失败，先看错误并评估所需规则，不要连带刷固件或关闭其他系统保护。

以后改管理机地址：在小度语音应用的“连接设置”里填写 `http://管理机IP:8766`，下次通话生效。不要把 `127.0.0.1` 填成管理机地址；从小度看它指的是小度本机。

## 4. 安装 CLI 与 Skill

```sh
mkdir -p ~/.local/bin
install -m 755 client/xiaodu client/codex-conversations ~/.local/bin/
export PATH="$HOME/.local/bin:$PATH"
export XIAODU_URL=http://设备IP:8765
xiaodu health
codex-conversations health
```

把 `skills/xiaodu-announce` 整个目录放入自己 Agent 的 Skill 目录；其中自带独立 CLI，其他局域网 Agent 不需要 ADB 或项目源码。每个 Agent 只需知道设备地址，详见 [控制接口](control.md)。

主对话的提示词可参考 [examples/MAIN_AGENT.md](../examples/MAIN_AGENT.md)，合并到你自己的 `AGENTS.md`；不要覆盖原有指令。`codex-conversations` 需要在后台 Codex 所用环境的 PATH 中可用，模型才能用它挂断或委派任务。

## 5. 本人验收

先确认 `xiaodu voice` 返回 `wake_ready`。在日常位置说“真维斯”：应听到完整双音、时钟退出显示光球，联网后模型打招呼。直接继续交谈，尝试插话，再说挂断；结束后应回到 `wake_ready`。

按 [验收文档](validation.md)逐项记录实际结果。首次安装与账号权限不同，安装命令成功不等于已通过声学和云端功能验收。

# 快速开始

## 准备条件

- 小度智能屏 **1S / XDH-29-B1**，Android API 27、ARMv7，已 root、有 Magisk 与其 BusyBox；原厂 DCS 服务仍可用且已激活。已验证宿主是 `com.baidu.duershow.dcs` **6.0.15.0 / versionCode 60000**，安装脚本对其他版本会停止，避免误用厂商私有接口。
- Linux 管理机和小度在互通的可信局域网，双方能联网。给设备和管理机保留稳定的 DHCP 地址更方便。
- Python 3.10+、ADB；若从源码编译，还需 JDK 17、aapt、zipalign、apksigner。
- 管理机上的 Codex 已登录，已有要接入的主对话和正在工作的 app-server。不要为了接入而复制别人的 `auth.json` 或登录令牌。

**先阅读 [Codex 环境准备](codex-setup.md)核对条件。** 里面给出已存在服务和首次独立部署两条路径，包括 Unix socket 启动命令、主对话 ID 的只读查询、后台工具权限与 CLI 环境。只有装了 Codex CLI 或能进行文字对话，不代表已具备本 Kit 需要的实验性实时接口。

## 1. 下载或构建

推荐从 [Release](https://github.com/NoobMaster233/xiaodu-jarvis/releases/tag/v0.1.1)下载 `xiaodu-jarvis-v0.1.1.zip` 和 `SHA256SUMS`，校验后解压。工具包已经带本项目的 APK 和服务 JAR，第三方运行依赖按固定 SHA256 下载。

```sh
sha256sum -c SHA256SUMS --ignore-missing
unzip xiaodu-jarvis-v0.1.1.zip
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

先按 [Codex 环境准备](codex-setup.md)启动/连接 app-server，用 `tools/codex_info.py` 只读取得主对话 ID；该工具不要求 JSON 已填完。然后用编辑器修改该 JSON：

| 字段 | 填什么 |
|---|---|
| `main_thread_id` | 你自己的已有 Codex 主对话 ID，来自所用 Codex 客户端或对话记录 |
| `device_url` | 小度局域网地址，例如 `http://设备IP:8765` |
| `voice` | 默认 `juniper`，实验性协议支持情况以所用 Codex 版本为准 |
| `listen_host` / `listen_port` | 桥接监听地址，默认 `0.0.0.0:8766` |
| `codex_socket` | 一般留 `null` 自动发现；多个 app-server 时填目标 Unix socket 的绝对路径 |
| 三个模型相关字段 | 留 `null` 保留自己主对话的现有设置 |

可用 `XIAODU_CONFIG` 指定其他配置文件。桥接状态写在 `~/.local/state/xiaodu-jarvis`，可用配置中的 `state_dir` 改路径。这里没有新增局域网密码、token 或配对流程。

先在前台运行，便于看是否找到 Codex 与所填主对话：

```sh
.venv/bin/python system/codex-bridge/bridge.py
```

另一个终端检查 `curl http://127.0.0.1:8766/health`，应同时看到 `ready=true`、`rpc_ready=true`、`main_ready=true`，且 `connection_error`、`main_error` 为空。只有 RPC 连上而主对话恢复失败，不能继续当成就绪。小度未安装前，设备状态请求失败是正常的，不能算实时链路已经通过。

先在前台终端按 Ctrl+C 退出这一份桥接，再安装用户级 systemd 服务；示例文件假定目录为 `~/xiaodu-jarvis`，路径不同时先修改 `WorkingDirectory` 和 `ExecStart` 中的目录：

```sh
mkdir -p ~/.config/systemd/user
cp system/codex-bridge/xiaodu-codex-bridge.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now xiaodu-codex-bridge
```

需要无登录会话持续运行时，由系统管理员执行 `loginctl enable-linger 你的用户名`。不要同时运行前台桥接与 systemd 两个实例。

## 3. 首次安装设备组件

ADB 可通过 USB 或你已经配置好的无线 ADB 连接。先用 `adb devices` 找到目标 serial，命令必须明确指定，工具不自动猜家里的设备。

```sh
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766
```

上面只显示计划，不碰设备。确认型号和目标地址后，加 `--apply` 才执行：

```sh
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766 --apply
```

脚本检查机型/API/ABI/root/Magisk/DCS 版本，核对源码与构建证明，校验 APK，安装语音和时钟，授予必要权限，将桥接地址存到语音应用私有目录，在临时目录解包校验后原子安装设备模块，再只读等待 `wake_ready`。时钟默认会启用自己的屏保，并把白天闲置时间设为 120 秒；**不需要时钟时，加 `--no-clock` 跳过其安装、授权和设置。**不会发起测试通话、录音或整机重启。若存在同名模块会拒绝覆盖；签名冲突也不会自动卸载旧应用。它是**首次安装辅助工具**，不是跨机型刷机或通用升级器。临时解包失败可以直接重试；正式模块已激活却未就绪时会保留并报错，按[维护手册](maintenance.md)排查。

已有安装可只读检查，不播放声音、不开始通话：

```sh
python3 tools/device.py --serial 你的SERIAL --check
```

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

上面的 export 只影响当前终端，不会自动传给已经运行的 Codex。请完成 [Codex 环境与 CLI 检查](codex-setup.md#让实际-agent-找到-cli)，或在自己的规则中使用 CLI 的绝对路径与 `--url`。

主对话的提示词可参考 [examples/MAIN_AGENT.md](../examples/MAIN_AGENT.md)，合并到你自己的 `AGENTS.md`；不要覆盖原有指令。实时通话提示词会提供本仓库 CLI 的绝对命令与正确桥接地址，不依赖后来临时添加的 PATH；普通设备控制仍需在 Skill 或规则中提供自己的设备地址。

## 5. 本人验收

先确认 `xiaodu voice` 返回 `wake_ready`。在日常位置说“真维斯”：应听到完整双音并显示光球，联网后模型打招呼；安装并启用时钟时，时钟应退出。直接继续交谈，尝试插话，再说挂断；结束后应回到 `wake_ready`。

按 [验收文档](validation.md)逐项记录实际结果。首次安装与账号权限不同，安装命令成功不等于已通过声学和云端功能验收。

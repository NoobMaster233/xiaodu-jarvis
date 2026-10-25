# Codex 环境准备：从服务到主对话 ID

这篇回答安装前最容易卡住的三个问题：Codex 服务怎么启动、主对话 ID 从哪里拿、语音 Agent 到底在哪个环境执行 CLI。

## 需要的是哪个 Codex

需要 Linux 上已登录的 Codex CLI/app-server，且该版本与账号支持 `thread/realtime/*` 的实验性 v3/WebRTC。它不是一个只有文字转发的通用 HTTP API，也不等同于公开 Realtime API 的接法。

本项目实际使用过 0.163/0.164 的 app-server。本次复核的 CLI 0.162.1 帮助中也有 Unix socket 和远程 TUI 参数，但**CLI 的版本不一定等于正在运行的 daemon 版本**，有这些参数也不能证明账号开放了实时语音。工具不会升级或重启你的 Codex。

```sh
codex --version
codex app-server --help
codex app-server daemon version
```

最后一条只适用于使用受管 daemon 的环境。没有 daemon 时不要为了跑这条命令擅自迁移现有工作。

Unix WebSocket 的基本协议见 [OpenAI 官方 App Server 文档](https://developers.openai.com/codex/app-server/)。本文命令还与上述本机 CLI 帮助逐项核对；完整实时功能仍需本人最终验收。

## 路径 A：已经有正在工作的 app-server

继续用它，保持相同 Linux 用户、相同 Codex 数据目录和主对话。不另外启动第二份服务去接管一个正在工作的 thread。

先完成快速开始里的下载和 `.venv` 依赖安装，然后只读列出现有对话：

```sh
.venv/bin/python tools/codex_info.py
# 如果 socket 不在默认发现目录，明确指定：
.venv/bin/python tools/codex_info.py --socket /你的/app-server.sock
```

成功输出形如：

```json
{"connected":true,"read_only":true,"threads":[{"id":"你的主对话ID","name":"主对话标题","cwd":"你的工作目录"}]}
```

选择你要沿用的那条 `id` 填进 `config.json.main_thread_id`。工具仅使用 `thread/list` 并分页，不创建对话、不发送消息、不恢复主对话、不改模型，也不保存结果；不会显示转写或对话正文。

默认查找 `/tmp/codex-daemon-当前UID/`，并检查监听者是同用户的 Codex app-server。只有 management socket、只有 stdio，或者有多份 app-server 而目标不明确时会报错；通过 `--socket` 和配置 `codex_socket` 指定真正的 RPC 套接字，不要随意挑一个名字像 Codex 的文件。

## 路径 B：首次独立部署，没有正在工作的 app-server

先按 Codex 的正常方式安装并 `codex login`。登录必须用自己的账号；本 Kit 不处理登录令牌。确认 CLI 帮助支持 Unix transport 后，在一个终端启动：

```sh
mkdir -p "$HOME/.local/state/xiaodu-jarvis"
chmod 700 "$HOME/.local/state/xiaodu-jarvis"
codex app-server --listen "unix://$HOME/.local/state/xiaodu-jarvis/codex.sock"
```

保持这个终端运行。另一个终端进入你希望 Agent 工作的目录，连接**同一份服务**：

```sh
codex --remote "unix://$HOME/.local/state/xiaodu-jarvis/codex.sock"
```

在 TUI 里建立一条你要作为主对话的普通对话，再从 Kit 目录只读查询：

```sh
.venv/bin/python tools/codex_info.py --socket "$HOME/.local/state/xiaodu-jarvis/codex.sock"
```

把返回的主对话 ID 填入配置，`codex_socket` 填入展开后的 socket **绝对路径**，例如 `/home/你的用户/.local/state/xiaodu-jarvis/codex.sock`；不是 `unix://` URL。不要把 stdio 的 `codex app-server` 当成已经启动了 Unix 服务。

如果想长期运行 app-server，按自己已有的进程管理方式管理上面这条命令；Kit 的 systemd 文件只管理 Python 桥接，不替你安装、升级或管理 Codex daemon。

## 工具权限与审批

桥接不会代替用户批准 Codex 的命令、文件或网络权限请求。语音能聊天，不代表后台工具获得了全部权限。需要人工审批的环境，要保留能处理审批的 Codex 客户端/TUI，并沿用自己的权限策略；任务停在审批时去该客户端确认，不反复发同一个工具请求。

本 Kit 不要求为了接入而关闭 sandbox 或批准所有命令。具体审批协议可参见上面的官方文档。

## 让实际 Agent 找到 CLI

在另一个安装终端执行 `export PATH=...` 或 `export XIAODU_URL=...`，不会改变已运行的 Codex 进程环境。两种做法选一种：

1. 你自己管理 Codex 的启动环境时，把 CLI 安装目录、设备地址放入该启动环境；下一次按自己的维护安排启动时生效。
2. 对已有服务更直接：在主对话规则和 Skill 中提供**绝对 CLI 路径及显式地址**，不依赖环境继承，例如：

```sh
python3 /你的绝对目录/xiaodu-jarvis/client/xiaodu --url http://device.example:8765 health
python3 /你的绝对目录/xiaodu-jarvis/client/codex-conversations --url http://127.0.0.1:8766 health
```

先替换路径和设备地址，再让**实际主对话的工具环境**各执行一次上述无副作用检查。终端里能运行不代替这一步。

实时启动提示只说明当前使用原生语音并沿用主对话规则，不重复维护工具命令。请按[主对话示例](../examples/MAIN_AGENT.md)将CLI绝对路径和地址写入自己的按需工具说明，安装小度Skill，让实际Agent能执行只读health。日常挂断使用设备CLI，管理机voice-end仅作HTTP不可用时的维护兜底。

## 配置后的验收门槛

启动桥接后，检查 `/health` 的 `rpc_ready`、`main_ready` 和 `ready` 都为 true；`main_error`、`connection_error` 为空。`rpc_ready=true, main_ready=false` 表示协议连上了，但所填主对话不能正常恢复，先修 ID/账号/数据目录问题。

这只确认控制链路和主对话恢复成功。没有发起实时通话时，不能据此宣称云端实时音频权限、ICE 或声音已经通过。

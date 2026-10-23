# HTTP、CLI 与 Skill

## 可信局域网直接调用

设备 HTTP 监听 `8765`，没有登录、token 或配对。只用于你自己可信的局域网，不应转发到公网。地址由部署者提供；`device.example` 是示例域名，必须换成真实设备地址。

```sh
export XIAODU_URL=http://设备IP:8765
python3 client/xiaodu health
python3 client/xiaodu volume 30
python3 client/xiaodu brightness 40
python3 client/xiaodu media pause
python3 client/xiaodu screen clock
```

CLI 仅依赖 Python 标准库，复制单文件即可用。也可逐次指定：

```sh
python3 client/xiaodu --url http://device.example:8765 status
```

## 常用设备接口

| 接口 | 方法 / 示例请求 | 作用 |
|---|---|---|
| `/health` | GET | 服务与合成器状态 |
| `/v1/device` | GET | 汇总状态 |
| `/v1/volume` | GET / POST `{"value":30}` | 媒体音量与回读 |
| `/v1/brightness` | GET / POST `{"value":40}` | 手动亮度比例与回读 |
| `/v1/media` | GET / POST `{"action":"pause"}` | 播放器能力与控制 |
| `/v1/screen` | GET / POST `{"action":"clock"}` | 屏幕、时钟、主页 |
| `/v1/apps` | GET | 应用别名 |
| `/v1/app` | GET / POST `{"action":"open","app":"settings"}` | 前台应用 / 打开 |
| `/v1/screenshot` | GET | 按需 PNG 截图 |
| `/v1/voice/status` | GET | 唤醒 / 实时状态 |
| `/v1/voice/end` | POST `{}` | 结束语音并核验 |
| `/v1/speech` | POST `{"text":"任务完成"}` | 排队文字播报 |
| `/v1/speech/请求ID` | GET / DELETE | 查询 / 停止该播报 |

CLI 的媒体动作是 `play/pause/toggle/next/previous/stop`，可用 `--app netease|bilibili|vlc` 明确播放器。`verified=true` 才表示观察到相应状态或曲目变化；`sent=true, verified=false` 只说明发送了动作，不自动重试切歌。没有媒体会话或能力时会明确失败，不猜坐标，也不拿另一个后台播放器冒充目标。

应用别名仅指向已知包名，不附带网易云、B站等第三方 APK，也不保证设备已安装这些应用。截图只在用户要求或当前任务确需时调用，不持续采集。

**所有设备控制都是一次性的。** 本次设置并回读成功后就结束；后续用户、其他 Agent 或应用改变状态时，不得按历史指令改回，不建立后台维持任务，也不在收尾恢复旧值。

播报跟随当前媒体音量。`say` 默认等待完成，`--no-wait` 仅代表排队；相同请求 ID 在内存缓存范围内去重，重启不恢复队列。结果不确定时不自动重播。

## 给另一个 Agent 使用

复制完整的 `skills/xiaodu-announce/`，让 Agent 知道设备地址，例如在运行环境设置 `XIAODU_URL`。Skill 自带 `scripts/xiaodu`，不依赖这个仓库、全局 CLI、ADB 或其他主机代理。

主对话的普通公开回复由桥接自动播报，实时通话期间由原生语音呈现，不要重复 `say`。后台项目会话只回报主对话；其他 Agent 仅在用户要求时主动播报。控制音量、亮度或切歌本身不会额外说话。

## Codex 对话 CLI

`client/codex-conversations` 优先使用 `--url`，其次 `CODEX_BRIDGE_URL`，再读取 `XIAODU_CONFIG` 或默认桥接 JSON 的监听地址/端口；没有配置时访问 `127.0.0.1:8766`，用于查看已有对话、委派、状态和挂断：

```sh
codex-conversations list --search 项目名
codex-conversations read 对话ID
codex-conversations send --thread 对话ID --message '请检查这个项目的状态'
codex-conversations tasks
codex-conversations voice-status
codex-conversations voice-end
```

自定义端口无需改源码，例如 `codex-conversations --url http://127.0.0.1:9876 voice-status`。它访问你已经登录的 Codex，本身不保存登录凭据；桥接接口可触发 Agent 工作，因此同样限于可信主机与局域网。

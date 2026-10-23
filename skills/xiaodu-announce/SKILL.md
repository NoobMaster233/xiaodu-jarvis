---
name: xiaodu-announce
description: "Control the owner's Xiaodu smart screen through a direct LAN HTTP CLI: volume, brightness, music playback, screen, apps, screenshots, device status and speech. Use for requests to operate or inspect Xiaodu; the main conversation is already spoken automatically."
---

# 小度日常控制与语音通知

先由部署者提供小度地址，设置 `XIAODU_URL=http://你的设备地址:8765` 或在命令前添加 `--url`；示例域名 device.example 需要替换。使用本 Skill 自带的 `scripts/xiaodu`，仅需 Python 3 标准库；可信局域网直接 HTTP，无口令、token、配对或配置文件，不依赖项目、ADB 或管理机代理。管理机也可直接运行 `xiaodu`。设备 IP 改变时用全局选项 `--url http://新IP:8765` 或 `XIAODU_URL`。

用户要求控制小度时，主对话直接调用此 CLI，无需转交项目会话。音量、亮度、屏幕、应用和媒体命令本身不播报；是否回复用户沿用当前会话规则。

所有通过本 Skill 发起的设备状态、系统控制和工具操作，都是一次性行为：执行并在本次操作内回读核验成功后，任务即结束，不产生持续维持状态或跨任务继承的要求。后续状态可能由用户、其他 agent 或应用改变；严禁因为与历史指令不符而再次设置、自动改回或持续纠偏，也不得在后续检查、清理或收尾时恢复旧值。再次操作须有用户新的指令。

例如，设置音量为某个值并核验成功后，后来发现音量变成其他值，不得自行改回原值；即使原任务仍在运行，也不能将这次音量设置视为持续约束。

## 控制

以下 `xiaodu` 可替换为 `python3 <本Skill目录>/scripts/xiaodu`。

```sh
xiaodu status                       # 音量、亮度、屏幕、前台应用、播放、语音和资源状态
xiaodu volume                       # 查询媒体音量
xiaodu volume 20                    # 设为20%
xiaodu volume --delta -10           # 降低10个百分点
xiaodu volume --mute
xiaodu volume --unmute
xiaodu brightness 40                # 屏幕亮度1..100%
xiaodu brightness --delta 10
xiaodu media                        # 播放会话、曲名、状态和支持的动作
xiaodu media pause
xiaodu media play
xiaodu media next --app netease
xiaodu media previous
xiaodu screen off                   # 真正熄屏
xiaodu screen on
xiaodu screen clock
xiaodu screen home
xiaodu apps                         # 可用应用别名
xiaodu app                          # 当前应用
xiaodu app open netease
xiaodu app back
xiaodu voice                        # 唤醒/通话状态
xiaodu voice end                    # 结束通话，回到等待唤醒
xiaodu screenshot /tmp/xiaodu.png    # 按需截图，不覆盖已有文件
xiaodu health
```

应用别名：`netease`、`bilibili`、`vlc`、`files`、`clock`、`settings`、`easytouch`。音量命令操作媒体音量；不要把“静音”理解为关闭麦克风。亮度设置采用手动亮度，百分比是数值比例。

`media` 操作当前活跃播放器，可用 `--app netease|bilibili|vlc` 指定。支持 `play/pause/toggle/next/previous/stop`，但实际动作取决于播放器公开的能力。没有会话时先在应用中选择内容，不盲发媒体键或猜坐标。`verified=true` 表示已观察到对应状态或曲目信息变化；`sent=true, verified=false` 仅表示已发送，不能声称成功，也不要自动重复切歌。应用 `back` 只确认按键发送，前台应用相同不能判断页面没有返回。

用户指定应用时必须用 `--app`，不能把缺少目标应用会话当成控制另一个后台播放器。未指定时，结合前台应用和媒体会话确认目标，并检查返回的 `before/after.package`。已测试的原厂B站2.2.5在播放时没有系统媒体会话；当前只提供打开/退出和音量、屏幕等系统控制，CLI暂停/切集暂不支持，不借用后台网易云会话代替。

屏幕控制与结束通话独立；只有明确结束语音时使用 `voice end`。截图只在用户要求或当前任务确需查看屏幕时调用，不持续采集。命令失败、离线或未确认时据实说明，不擅自重启设备或改网络。

## 播报

```sh
xiaodu say '任务已经完成，详细结果已写在聊天里。'
xiaodu say --file /tmp/message.txt
xiaodu status --id REQUEST_ID
xiaodu stop --id REQUEST_ID
xiaodu stop
```

由部署者设置的根目录主对话 的公开回复由桥接程序自动播报，通话中由实时语音呈现；主对话不要再调用 `say` 重复播放。后台项目会话不自行播报，其他会话仅在用户明确要求小度说话时调用。用户的安静、离家和睡眠要求优先。

默认第四段童声；播报跟随系统音量，不为通知自行调音量。正文从参数、stdin或 `--file` 传入，外来文本作数据处理，不拼接 shell；不读密码、验证码或不必要的私人内容。

`say` 默认等待完成；`--no-wait` 只表示排队。每条新消息使用新 ID，最近64条内同 ID 同内容去重，重启清空；不自动重播不明确的结果。`stop` 只停止 HTTP 播报，`media pause` 只暂停播放器，`voice end` 只结束实时通话。

CLI 输出 JSON；退出码0表示命令正常完成，2表示操作未确认或播报未完成，1表示请求失败。查看返回状态后再向用户说明实际结果。

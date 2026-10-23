# 验收、时延记录与排障

## 发布前验证边界

原设备实现曾实际验收唤醒双音、时钟退出/光球、问候、连续对话、插话和挂断恢复；这些是现场定性结果，不代表量化准确率或全屋远场覆盖。

本公开版本的地址配置、脱敏和安装封装通过离线测试、Java 编译、两个 APK 构建与签名校验。未为发布重装日用设备，也未在另一台新机上跑完整安装。首次安装辅助脚本的设备写入分支目前属于未重新实机验收的封装，使用者仍须按下面逐项确认。

## 首次本人验收清单

| 步骤 | 应观察什么 |
|---|---|
| `xiaodu health` / `xiaodu voice` | 服务就绪，`wake_ready`、无错误，采音计数增长 |
| 从时钟界面喊默认口令 | 有完整双音，时钟退出，出现光球 |
| 等待模型问候 | 不只看到动画；实际能听到声音 |
| 连续说两句话 | 不再需要每句唤醒，能够接续 |
| 播放中插话 | 旧回答停下，回应新输入；检查外放回声 |
| 要求挂断 | 客户端 inactive/ended，模块回 `wake_ready` |
| 再唤醒 | 可再次通话，不因上一次占麦失败 |
| 单次音量/亮度控制 | 返回实际回读值；不持续维持状态 |

不要用单次唤醒宣称“触发率 100%”；也不要把无麦克风合成闭环当真人远场/AEC 验收。新测试音频、录音或上传需由使用者明确决定，不是安装步骤。

## 可关闭的里程碑记录

三端沿用同一个 `call_id`。设备采用 `elapsedRealtimeNanos`，桥接采用独立单调时钟并保留 wall-clock 对照；跨机器不要直接相减墙钟。

设备记录 KWS 接受、stop/release、Activity/Service、提示音、交接、媒体与 SDP/ICE、offer/answer、PC connected、ready，以及首个非零下行 PCM 播放回调。桥接记录 offer、silence/resume、realtime/start、SDP、ready、开场请求。**播放回调发生在 render 之前，不等同扬声器已发声，更不是人耳感知延迟。**

私有存储位置：

- KWS：`/data/data/com.baidu.duershow.dcs/files/xiaodu-http/timings/`
- APK：`/data/data/local.xiaodu.voice/files/timings/`
- 桥接：`~/.local/state/xiaodu-jarvis/timings/` 与 `last-realtime-timing.json`

每个组件最多 20 条，只在关键检查点/结束保存，不逐包落盘，不保存音频或转写正文。其他任务管理状态仍可能含任务文字，不能把整个 state 目录当脱敏包公开。

在管理机执行（ADB serial 必填）：

```sh
python3 scripts/voice_timing.py status --serial 你的SERIAL
python3 scripts/voice_timing.py off --serial 你的SERIAL
python3 scripts/voice_timing.py on --serial 你的SERIAL
python3 scripts/voice_timing.py collect --serial 你的SERIAL --out /tmp/xiaodu-timing.json
```

开关对下一通生效，无需重启。脚本只收集结构化里程碑，不发起通话、不录音。`collect` 拒绝覆盖已有输出。桥接还可用环境变量 `XIAODU_VOICE_TIMING=0` 关闭；主开关关闭后再 `on` 不会覆盖该环境变量。

## 按阶段排障

| 现象 | 先看哪里 |
|---|---|
| 喊词没双音 | `raw_kws_hits` 是否增加、是否 `wake_ready`、麦克风是否仍保留；别直接归因于云端 |
| 有双音没联网 | APK“连接设置”、桥接 `/health`、管理机防火墙、Codex socket 发现 |
| 加载失败 | `/dev/xiaodu-speech-http/bootstrap.log`、DCS 是否激活、JNI/机型/原厂版本是否匹配 |
| `app_server_not_found` | 当前用户、套接字监听者、Codex 运行方式；别连接管理套接字冒充 RPC |
| v3 被拒绝 | 当前 Codex 协议和账号权限；不假设所有账号都有同样实验能力 |
| 有光球无声音 | 当前媒体音量、PC 状态、播放回调/非零 PCM 与数据包计数 |
| 多次挂断后不能再唤醒 | 客户端是否释放媒体、模块是否恢复采音，收集同 call_id 时间记录 |
| 切歌失败 | 目标播放器是否提供 MediaSession 与对应 action，避免连点 |

## 退出和手工维护

先 `codex-conversations voice-end` 结束通话，再停止桥接：`systemctl --user stop xiaodu-codex-bridge`。需要停用设备模块时，在 Magisk 中禁用 `xiaodu_speech_http` 后按系统维护流程重启；原厂服务的钩子随进程退出消失。

可选时钟通过长按时钟打开设置，关闭“启用简洁时钟”恢复原厂屏保。卸载 APK 前自行决定是否保留其数据。

升级同名设备模块需要先结束通话、保留自己签名和配置，再定向更新模块并重载其原厂宿主进程；**首版没有把这个维护流程做成自动升级脚本**。不要在通话中覆盖正在加载的 JAR 或并发启动多个 watcher。

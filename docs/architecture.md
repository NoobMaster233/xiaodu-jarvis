# 架构与启动时序

## 三个位置，各做什么

**小度设备**常驻本地 KWS（sherpa-onnx JNI），16kHz 单声道、MIC 源 1、单线程。当前配置 `maxActivePaths=16 / threshold=0.10 / score=2` 是作者设备的经验值，不是所有房间的最优参数。原厂唤醒在自有入口工作期间被关闭，避免两套入口抢麦。设备内 HTTP 服务复用仍已激活的原厂语音进程；root 辅助进程提供有限的系统控制，HTTP 不提供任意 shell。

**语音 APK**在命中后显示光球并播放 240ms 双音，接管麦克风、创建 libwebrtc，配置软件 AEC/NS/AGC。双音是确定的本机反馈，不依赖模型先返回一句话。光球使用音频回调的电平，不保存声音。

**Linux 管理机**运行 Python 桥接，通过当前用户已有 Codex app-server 连接固定的主 thread。`thread/realtime/start` 以 v3/WebRTC 建立该 thread 的实时语音；后台工具执行模型与实时语音模型分工，不能把“同一 thread”理解成两个模型完全相同。桥接做信令和任务协调，不承担实时 PCM 转码。

## 启动中真正的并行关系

```mermaid
flowchart TD
  W[KWS 接受一次唤醒，生成 call_id] --> L[启动 Activity / Service]
  W --> S[KWS AudioRecord stop + release]
  L --> F[播放本地双音]
  L --> N[查询桥状态，向本机声明通话]
  S --> H[确认麦克风真实释放]
  N --> H
  H --> M[创建 ADM / Factory / PeerConnection]
  M --> SDP[local SDP / ICE gathering]
  SDP --> O[ICE complete 发送 offer]
  SDP -.仅超时兜底.-> T[1800ms 后发送已有候选]
  T --> B[桥接 silence → resume → realtime/start]
  O --> B
  F --> G[事件门：双音结束 + 释放确认 + 媒体就绪]
  M --> G
  B --> A[收到 answer]
  G --> R[允许真实录放音并应用 answer]
  A --> R
  R --> C[PC connected → ready]
  C --> Q[请求模型开场 → 首个远端音频]
```

媒体初始化可与双音剩余部分重叠，先关闭真实录放音；门控条件满足后才打开。不能在 KWS 仍占麦时开始 WebRTC AudioRecord，也不能把提示音播进上行。这里用释放确认和完成事件，而不是盲等一个固定交接时间。

1800ms 是 ICE gathering 没完成时的兜底，**不是每次启动固定等待 1.8 秒**。`ICE_COMPLETE` 到达就会提前发送 offer 并取消兜底。服务器的 resume、实时初始化、网络协商以及模型首音仍需真实测量；本地少等一段时间不等于整体首音一定同比变快。

## 对话、插话与退出

用户音频与模型声音在 WebRTC 中双向流动，AEC/NS/AGC 帮助处理外放；实际房间回声与远场质量需要本人验证。实时语音与同一主 thread 交互，需工具的任务交给 Codex 后台。普通文本空闲走 `turn/start`，忙时走 `turn/steer`，不另设排队语音总管。

用户要求挂断时，主对话调用 `codex-conversations voice-end`。桥接停止该 thread 的实时会话；设备观察结束，释放 PeerConnection、音频设备和唤醒锁，通知本地模块恢复 KWS。关闭音频不取消已经启动的后台工作。连接失败、客户端退出和桥接丢失也有收尾路径。

普通 HTTP 通知是独立的文字 TTS 路径。桥接只自动播报主对话的公开回复；实时通话中抑制这条路径，避免同一句话播两遍。后台项目会话只回报主对话。

## 不依赖什么

日常音频不走 ADB、Frida RPC 或管理机上的音频 worker。Frida inject 只在设备内加载 Java 服务，随后退出；watcher 仅做设备内加载恢复。Android APK 使用原生视图与 libwebrtc，没有 WebView 和大型前端框架。

# 唤醒方式

## 默认：原厂“小度小度”

声学检测复用原厂引擎。命中后，在原厂提示、ASR和业务分发前接走事件，暂停原厂唤醒会话，再启动自己的双音、光球与Codex实时语音。语音对话内容不再按原厂业务处理；挂断后恢复等待“小度小度”。当前只接管完整口令，不把短“小度”等其他成功事件转成新通话。

不需要给原厂创建新唤醒词，也不需要录制训练音频。原厂仍是闭源SDK，本项目只接管明确事件和停止回调，不宣称重做了声学算法或关闭了整机厂商网络行为。

```sh
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766 --apply
```

默认factory。状态应为`wake_source=factory`、`wake_ready`、`factory_wake_phase=ready`，`local_kws_active=false`和`samples=0`是预期。`max_active_paths/threshold/score`只是保留的本地模式参数，不影响原厂。

## 可选：本地“真维斯”

```sh
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766 --wake-source local
python3 tools/device.py --serial 你的SERIAL --bridge-url http://管理机IP:8766 --wake-source local --apply
```

只适用于首次安装；已有模块按维护流程处理，不覆盖运行中的模块。本地入口用sherpa-onnx，16kHz单声道/MIC源、单线程，参数16/0.10/2是已用过的候选，不是所有用户和房间的最优值。关键词来自examples/keywords.txt，改显示名不会自动训练出新发音。

两种模式都保留运行依赖，安装只选其中一套常驻。模式通过persist.xiaodu.wake_source一次性选择，loader启动时读取，不循环重写。高级维护中需要改变模式时，先结束通话、设为factory或local并定向重载DCS；日常CLI不提供自动切换两套唤醒的接口。

## 麦克风交接区别

| 模式 | 确认事件 | 不能推断什么 |
| --- | --- | --- |
| factory | 原厂SDK停止回调，release_kind=factory_sdk_stop_ack | 不等于Java录音器release、物理麦克风断电或整个共享音频前端关闭 |
| local | Java AudioRecord.release实际返回，release_kind=local_audio_record_release | 不等于关闭其他应用的麦克风 |

客户端等同一call_id的确认、双音结束释放和媒体准备三个条件满足后，再开启通话录放音。没有恢复固定250ms等待；1800ms只用于ICE超时兜底。

## 验收范围

原厂接管已经在作者日用设备获得双音、光球、回答和挂断的本人定性确认；没有用单次成功推算触发率、误唤醒率或全屋覆盖。新的公开配置/首次安装封装通过离线检查与构建，不冒充另一台新机安装通过。首次安装仍需喊词、对话、插话、挂断、再次唤醒。

# 维护手册

## 已验证基线与只读检查

| 项目 | 基线 |
|---|---|
| 设备 | 小度智能屏 1S / XDH-29-B1 / MT8167 |
| 平台 | Android 8.1 / API 27 / armeabi-v7a |
| 原厂语音宿主 | `com.baidu.duershow.dcs`，versionName **6.0.15.0**，versionCode **60000** |
| 原厂launcher | `com.baidu.launcher`，**1.68.2 / 10682** |
| Codex | v3 实验性实时协议；使用过 0.163/0.164 app-server，版本/账号仍需核对 |

DCS 版本来自保留的已验证原厂 APK 元数据，没有把该 APK 上传。仅有相同机型/API 不代表厂商私有类和字段一定相同；首次安装脚本对其他 DCS 版本停止。适配者需要另外核对 loader/合成接口，不能靠强行改一个版本字符串就宣称兼容。

```sh
python3 tools/device.py --serial 你的SERIAL --check
```

会检查型号/API/ABI/root/Magisk，返回DCS/launcher版本、模块是否存在，以及当前 `wake_ready/state/error/samples`。默认factory模式核对原厂phase=ready，不要求本地samples递增；local模式才确认两次采音计数递增。只读取，不安装、不挂断、不播报、不开始新通话。设备尚未装模块时 `state=unavailable` 是正常的。

原始版本可手工读取：

```sh
adb -s 你的SERIAL shell getprop ro.product.model
adb -s 你的SERIAL shell getprop ro.build.version.sdk
adb -s 你的SERIAL shell dumpsys package com.baidu.duershow.dcs
```

最后一条的本地输出可含其他系统元数据，提交 Issue 只摘 `versionName/versionCode`，不要整段上传。

## 三层健康不能混在一起

- 桥接 `/health` 的 `rpc_ready=true`：本机协议连接已建立。
- `main_ready=true` 和总 `ready=true`：所填主对话已正常恢复；不代表云端通话权限通过。
- 设备 `wake_ready`：本地唤醒运行中；仍需本人口令测试，不能当作唤醒准确率。

`xiaodu health` 只确认设备 HTTP/引擎，不单独证明 KWS 工作。可选local模式若遇到 `mic_unavailable` 或 `mic_read_failed` 退出，watcher 只看 HTTP 存活不会自动重建采音；这是一项已知限制。先排除其他应用占麦、结束当前通话，再按下文维护重载宿主，不要盲目叠加新的录音进程或重复注入。

## 首次安装中断后怎么继续

v0.1.1 先在本次专用临时目录解包、核 SHA256 和权限，成功后才原子改名到正式模块目录。

- **APK/授权/临时解包阶段失败，正式模块不存在**：修正具体错误后重跑原安装命令；不会自动卸载已装 APK。签名不同必须由你决定如何迁移数据。
- **正式模块已存在，但未就绪**：安装命令报错并保留模块。先运行 `--check`、查看日志，不能当成安装完成，也不能再覆盖一遍。
- **旧版 v0.1.0 留下的半成品模块**：不能只凭目录存在判定是否在运行。先看 watcher、DCS 和日志；不确定时按“停用设备模块”禁用并重启后，再处理已禁用目录。不要直接删除正在加载的 JAR。
- **安装进程被强制杀死后锁残留**：只有确认没有安装程序仍在执行时，手工 `rmdir /data/adb/xiaodu-jarvis-install.lock`（经设备 root shell）。正常失败会自行释放；不要把正在使用的锁强删。

安装器只清理本次生成的 staging 目录，不清除已有应用数据或旧模块。默认会启用待机时钟并设白天 120 秒；不希望改变屏保，首次安装就用 `--no-clock`。

## 查看日志

管理机：

```sh
systemctl --user status xiaodu-codex-bridge --no-pager
journalctl --user -u xiaodu-codex-bridge -n 80 --no-pager
curl http://127.0.0.1:8766/health
```

自定义监听端口时同步替换上面 curl 的地址；配套对话 CLI 会读取配置，也可显式 `--url`。

设备加载日志：

```sh
adb -s 你的SERIAL shell "su -c 'cat /dev/xiaodu-speech-http/bootstrap.log'"
adb -s 你的SERIAL shell pidof com.baidu.duershow.dcs
adb -s 你的SERIAL shell pidof xiaodu-control
```

看到加载成功只说明某一阶段成功；以 `--check` 和必要的本人测试补齐结果。分享日志前仍需检查私人路径、对话编号和错误信息。

## 维护重载设备宿主

仅当你明确要维修已失败的设备链路时使用，**会中断当前通话**，先从管理机执行 `codex-conversations voice-end`，再确认 `voice-status` 已结束。不要拿这组命令做例行轮询。

```sh
adb -s 你的SERIAL shell "su -c 'am force-stop com.baidu.duershow.dcs'"
adb -s 你的SERIAL shell "su -c 'am startservice -n com.baidu.duershow.dcs/.DcsService'"
```

`DcsService` 是从上述已验证版本的原厂 Manifest 读取的实际组件，DCS 没有 launcher Activity，不使用 monkey 猜入口。本轮没有在日用设备重跑这些重载命令；执行时检查命令输出，返回 Error 就停止排查。watcher 随后恢复本地装载还涉及其 30 秒检查周期，最终以 `--check` 确认为准。

修改桥接配置后，需要在结束通话的维护窗口执行 `systemctl --user restart xiaodu-codex-bridge` 才生效；配置不是热加载。更改监听地址/端口还要同步 APK“连接设置”。不要让改配置的终端误以为已经改变了运行进程。

## 临时停止与永久停用

临时停止管理机桥接：

```sh
codex-conversations voice-end
systemctl --user stop xiaodu-codex-bridge
```

不希望下次用户服务启动时恢复，使用：

```sh
systemctl --user disable --now xiaodu-codex-bridge
```

停止管理机桥接并不会停止小度上的本地 KWS。要停用设备模块，在 Magisk 中禁用 `xiaodu_speech_http`，或显式创建禁用标记后按正常维护流程重启：

```sh
adb -s 你的SERIAL shell "su -c 'touch /data/adb/modules/xiaodu_speech_http/disable'"
adb -s 你的SERIAL reboot
```

这组操作是供使用者主动维护的步骤，安装器不会自动执行。重启后原进程钩子已退出，再决定是否删除已禁用模块目录或卸载自有 APK。

时钟可长按打开设置，关闭“启用简洁时钟”。这会选择原厂 Dream，**不会自动恢复先前所有闲置超时设置**；有自定义设置时安装前自行记录，停用后按自己的记录恢复。

## 发布新版本的门槛

1. 在独立公开仓库修改源码和文档，更新 `VERSION`、`CHANGELOG.md` 与下载指引。
2. 跑测试、公开文件检查、编译检查；改了编译输入就重建，保存签名密钥在私有目录。
3. `tools/release.py` 核源码集合/内容/编译脚本和产物，再生成允许清单包；拒绝链接和越界输入。
4. 检查源码、解包资产与嵌套 APK；不把整个私有项目、真实日志或配置打包。
5. 用 gh 发布新的 tag/Release；从发布页重新下载核 SHA256，并确认 GitHub Actions 成功。修复版本保留旧 tag，不静默覆盖旧资产。

上述自动检查不代替新机首次安装或真人声学验收，结果应分别记录。

## 从 v0.1.x 升级到 v0.2.0

这是针对已有本Kit且签名一致的手工定向升级，不是让首次安装器覆盖旧模块。先保存自己的配置、旧代码/模块与签名资料；使用原Release签名时安装同源新版APK，自己编译时仍用自己的旧密钥。版本冲突不要自动卸载/清数据。

1. 用已配置地址的`xiaodu voice end`确认通话结束，再停管理机的用户级桥接服务。保持原厂launcher、DCS账号与激活状态。
2. 解压新工具包到单独目录，保留旧配置文件，不复制示例JSON覆盖自己的值。运行`python3 tools/prepare.py --group runtime`及`python3 tools/package_module.py`。
3. 新APK不启动通话，只更新原应用；桥接地址私有文件会保留。

```sh
adb -s 你的SERIAL install -r artifacts/voice-client/xiaodu-voice-1.7.apk
adb -s 你的SERIAL push artifacts/xiaodu-speech-module.zip /data/local/tmp/xiaodu-module-v020.zip
adb -s 你的SERIAL shell "su -c 'mkdir /data/adb/xiaodu-upgrade-v020 && chmod 700 /data/adb/xiaodu-upgrade-v020 && /data/adb/magisk/busybox unzip -q /data/local/tmp/xiaodu-module-v020.zip -d /data/adb/xiaodu-upgrade-v020 && cd /data/adb/xiaodu-upgrade-v020 && /data/adb/magisk/busybox sha256sum -c SHA256SUMS && chmod 700 service.sh watch.sh frida-inject'"
```

4. 读取watch与DCS的当前PID。将下面占位符换成读取到的数字，不能照抄其他设备的PID。停止watch会清理本模块root控制进程；确认其退出后再替换目录。

```sh
adb -s 你的SERIAL shell "su -c 'cat /dev/xiaodu-speech-http/watch.pid; pidof com.baidu.duershow.dcs'"
adb -s 你的SERIAL shell "su -c 'kill WATCH_PID'"
adb -s 你的SERIAL shell "su -c 'pidof xiaodu-control'"
```

5. 确认校验成功且没有活动通话/重复watch，备份旧目录并启用新模块。已有同名升级暂存或备份目录时先查明原因，不反复覆盖。此处一次性选factory；坚持本地自定义口令时改为local。

```sh
adb -s 你的SERIAL shell "su -c 'test ! -e /data/adb/xiaodu-backup-v01 && mv /data/adb/modules/xiaodu_speech_http /data/adb/xiaodu-backup-v01 && mv /data/adb/xiaodu-upgrade-v020 /data/adb/modules/xiaodu_speech_http'"
adb -s 你的SERIAL shell "su -c 'setprop persist.xiaodu.wake_source factory && kill DCS_PID && sh /data/adb/modules/xiaodu_speech_http/service.sh'"
python3 tools/device.py --serial 你的SERIAL --check
```

6. 把用户服务WorkingDirectory/ExecStart指向新目录，保留原配置，启动一份桥接。更新各Agent安装的Skill/CLI，合并新版AGENTS示例；用health和默认“小度小度”完成本人验收。无需重新安装时钟或重启整机。

升级步骤没有为发布在另一台新机完整实操；源码/包/隔离ADB测试不替代现场检查。失败时保留日志、旧模块与自己的配置，先定位阶段，不重复安装或扩大到刷机。

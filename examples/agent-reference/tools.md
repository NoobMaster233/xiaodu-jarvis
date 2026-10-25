# 工具说明模板

由部署者填写下面CLI的绝对路径、设备URL和桥接地址，使实际Agent能直接调用。此文件供AGENTS.md按需索引，不向桥接里复制整套长期规则。

| 工具 | 用途 |
| --- | --- |
| codex-conversations | 查看已有对话、委派/引导、查询任务和中断指定回合 |
| xiaodu | 通过已安装Skill直接控制小度 |

```sh
python3 /你的目录/xiaodu-jarvis/client/codex-conversations --url http://127.0.0.1:8766 list --search 项目名
python3 /你的目录/xiaodu-jarvis/client/codex-conversations --url http://127.0.0.1:8766 read 对话ID
python3 /你的目录/xiaodu-jarvis/client/codex-conversations --url http://127.0.0.1:8766 send --thread 对话ID --message '任务目标和验收要求'
python3 /你的目录/xiaodu-jarvis/client/xiaodu --url http://device.example:8765 voice
python3 /你的目录/xiaodu-jarvis/client/xiaodu --url http://device.example:8765 voice end
```

send对忙碌目标使用steer，对空闲目标启动回合；一般改口无需先interrupt。tasks只包含本桥接委派，不代表全部对话。list最多100个匹配项、read最多5轮，需要时结合项目记录。结果不明先查询，不重复发送。

主对话负责核对自动回传结果再回复用户。日常挂断用xiaodu voice end，只有设备HTTP不可用才用codex-conversations voice-end维护兜底。health退出码0不等于ready，需检查rpc_ready和main_ready。

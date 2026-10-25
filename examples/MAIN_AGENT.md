# 主对话规则部署

将[AGENTS.md.example](AGENTS.md.example)中需要的职责合并到自己主对话工作目录的AGENTS.md，不覆盖原有指令。将[工具说明](agent-reference/tools.md)放到同目录的agent-reference/tools.md；环境资料按[模板](agent-reference/environment.md)填写成agent-reference/environment.md。索引使用相对路径，复制后核对目标确实存在。

先在实际Agent环境配置两个CLI和小度Skill。其他终端的export不会自动传给已运行的Codex。工具说明中的路径/地址都是占位符，换成自己的；不要照抄作者设备数据。

```sh
python3 /你的目录/xiaodu-jarvis/client/xiaodu --url http://device.example:8765 health
python3 /你的目录/xiaodu-jarvis/client/codex-conversations --url http://127.0.0.1:8766 health
```

实际Agent能够成功调用这两个只读命令后，再使用声学验收清单。提示词只规定职责和操作方式，不是定时器；长时间沉默自动挂断没有新增计时实现。

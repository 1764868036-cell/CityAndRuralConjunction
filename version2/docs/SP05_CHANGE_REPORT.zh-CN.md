# SP05 REST Connector 改动报告

日期：2026-10-19。目标是借鉴 Camunda_Postman_Student_Tutorial.pdf 的 API 请求与变量映射机制，将已有外部资源可用性查询改为真实 HTTP 调用。

## 实现

- SP05_RequestAvailability 改为 REST Connector（io.camunda:http-json:1），GET 查询可用性；输入来自原流程变量，输出仅映射 externalAvailable。
- 移除初次查询的消息捕获事件 SP05_Catch_Availability、关联 Flow_088 及两条消息流，连接现有可用性网关。
- 保留预订、待处理和资源释放通知语义；原 Java 查询 Worker 保留支持历史版本。
- 生成器末尾应用幂等的 apply_sp05_rest.py，保留重试退避配置；更新 Worker/Connector 类型校验。
- 清除回退模型的空 BPMNDiagram 壳，修复 Schema 校验；校验器识别 SP13 内层子流程的独立图层。
- 新增 SP05 专用 Postman 集合、本地 Mock、部署指南、Runtime 启动辅助脚本和验证脚本。

## 验证

静态：部署/表单/Worker 契约测试 6 项通过，模型包校验通过。

真实集群：Camunda 8.9.0，本地 Postman Mock，真实 HTTP Connector 和 Java Worker。available 场景从流程开始完整结束；unavailable 分段场景进入待处理、收到模拟释放通知后预订；503 分段场景产生预期 incident 且没有误创建预订。测试分段实例已在证据采集后取消。

网页：新实例 2251799813707964，在真实 Edge Tasklist 中完成 12 次任务提交、涉及 9 种表单，包括两轮信件处理。实例最终 COMPLETED、0 incident，SP05 返回 true 并生成外部预订。启动实例与消息发布通过 API；人工任务通过网页操作，没有用 API 完成代替表单填写。

详细记录：[API 场景](../evidence/SP05_E2E.zh-CN.md)、[网页流程](../evidence/SP05_WEB_E2E.zh-CN.md)。JSON 证据与截图均在 evidence/。

## 问题、修正和边界

首次调用因错误使用 SECRETS_ 前缀失败。经真实实例复现和官方文档核对，改为 Camunda 8.9 默认的 SECRET_SP05_AVAILABILITY_URL；重启 Runtime 后恢复原实例并完成流程。

云端 Mock 已创建，但此前 HTTPS 握手失败，本轮通过的是本地接口。Runtime 入站扫描仍有 Schema 初始化错误，未阻断本轮出站 HTTP，不能由此认为入站能力已验证正常。

Connector 不写入原 Java Worker 的 H2 event_log。第二轮信件表单会预填第一轮审批字段，本轮人工核对并提交，但未验证独立信件审批数据隔离。使用同一 demo 账号测试，未验证候选组权限隔离。

本轮结果不覆盖全部 37 种表单、全部异常分支，也不替代旧验收计划。evidence/acceptance-input/ 中旧快照及旧测试结果保持其历史含义。

## 远程合入策略

在远程最新 main 上应用本轮修改，保留远程 iStar/goalModel 等更新。此前删除的旧 Postman 文件不恢复，新提交只提供 SP05 实现。本地 IDE 配置、数据库锁文件和无关目录不提交。部署与操作步骤见 [Postman 指南](../postman/README.zh-CN.md)。

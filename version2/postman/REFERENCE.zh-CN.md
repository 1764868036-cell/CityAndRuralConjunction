# Version 2 Postman 进阶参考

[返回新手教程](README.zh-CN.md) | [English](REFERENCE.md)

本集合包含 36 个请求，覆盖当前主 BPMN 接收的 22 种消息；部署资源为 1 个 BPMN 和 37 个表单。

## Version 2 的关键规则

- 启动顺序是 **创建实例 → 发 `HPAS: Referral received` → 填写转诊表单**。与第一版“先表单、再入口消息”的模型不同。
- 9 个事件子流程为 SP03B、SP09–SP16。可以在活动实例中触发，采用非中断方式；它们不是创建根实例的入口，也不是第一版互斥的入口路由。
- 所有本集合消息都使用 `HPAS: ` 前缀，统一按 **`referralId`** 关联，检验消息也一样。`patientId` 是业务数据，不是此版消息关联键。
- `HPAS: Scheduling result` 被三个等待点复用：主预约用 `slotFound`，预约变更用 `slotAcceptable`，随访用 `inRequestedPeriod`。集合提供三份分别命名的请求，但请求名称不会影响引擎路由；同一实例多个分支同时等待该消息时，现有模型不能精确指定接收方。分开测试这些场景，或确保只有目标等待点活动。
- 事件分支可能被 Worker 自动触发，例如门诊信函、退款和治疗方案更新。先查看活动节点，避免重复发送同一业务事件。
- SP16 使用 `eventKind=access` 或 `interruption`。当前模型没有“服务恢复”消息捕获点，因此集合不添加第一版的恢复请求。
- 正常关闭在 `Top_WaitClosure` 发送空变量；初诊前关闭在 `SP03_Catch_Close` 发送 `sp03Outcome=closed`。它们都不是通用取消实例操作。
- 创建请求使用最新活动的 `Process_HPAS` 定义。验收测试的冻结快照也使用这个 ID；测试期间不要混合部署，先确认版本，必要时重新部署当前主 BPMN。

## 环境与可选分支

| 变量 | 用途 |
| --- | --- |
| `baseUrl` | Camunda 地址 |
| `processDefinitionId` | 固定 `Process_HPAS` |
| `referralId` | 当前实例的消息关联标识；创建时为空则生成 `REF-V2-PM-...` |
| `patientId` | 当前患者标识；创建时为空则生成 `PAT-V2-PM-...` |
| `processInstanceKey` | 创建成功后保存，用于查询；可手动设置为已有实例的 key |
| `futureDate` | 请求局部变量，脚本生成 UTC 日期的 21 天后 |

每次独立测试前清空 `referralId`、`patientId` 或设置新的唯一值；同一实例运行中保持不变。调试已有实例时手动填入它的真实关联标识及实例键。

需要测试患者付款时，在创建请求中选择 `fundingRoute=patient-pay` 并提供数值类型的演示 `chargeAmount`；必须存在 `appointmentId`，正常预约 Worker 会生成它。选择 Payment completed / failed / confirmation lost 中的一种结果。退款则先在 `SP11_Determine` 记录 `refundDue` 与 `financeApprover`，等待 `SP11_Catch_Result` 后再发退款结果。集合中的数据仅供虚构演示。

消息 TTL 为 **900000 毫秒（15 分钟）**。每次 Send 生成新的 `messageId`，所以重复点击是新消息；测试去重时手动使用固定 ID。HTTP 200 只说明引擎接受发布，不能证明关联或业务完成。搜索存在索引延迟，可稍后重试；查询每页 100 条（流程定义 20 条），更多结果按返回游标分页。

## 验证与参考

集合含 HTTP 状态断言、返回 key 检查、查询数组检查，以及环境和关联键检查。本次检查覆盖 Postman v2.1 schema、全部请求 JSON、38 个部署文件、22 种消息与 BPMN 的对应关系及脚本执行。以上是集合添加时的静态与脚本检查范围，不代表本教程的 Postman + Tasklist 完整人工流程已经执行通过。

- [Version 2 说明](../README.zh-CN.md)
- [验收计划与变量映射](../docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md)
- [Worker 的实际模拟逻辑](../java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java)
- [Camunda 8.9 创建实例](https://docs.camunda.io/docs/8.9/apis-tools/orchestration-cluster-api-rest/specifications/create-process-instance/)
- [Camunda 8.9 消息关联与缓冲](https://docs.camunda.io/docs/8.9/components/concepts/messages/)

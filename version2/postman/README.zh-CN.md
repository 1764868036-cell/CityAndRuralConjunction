# Version 2 Postman 接口调试

[English](README.md) | **简体中文**

本目录提供 **36 个请求**，对应 `version2/hospital-patient-pathway.bpmn` 的 `Process_HPAS`。包含部署、实例创建、转诊入口、外部应答、9 个事件子流程、状态查询及关闭路径，覆盖可执行流程实际接收的全部 **22 种消息**。接口直接调用 Camunda 8.9；Java 项目提供 Job Worker，没有单独的患者 CRUD HTTP API。

## 导入与启动

1. 在 Postman 点 **Import**，导入本目录的 `HPAS_Version2.postman_collection.json` 和 `HPAS_Version2_Local.postman_environment.json`。
2. 选择 **Hospital Pathway - Version 2 HPAS - Local** 环境。`baseUrl` 默认为 `http://localhost:8080`，不带结尾 `/` 或 `/v2`。脚本会检查 `processDefinitionId=Process_HPAS`，避免误选第一版环境。
3. 启动 Camunda 8.9。集合默认 **No Auth**；若集群启用认证，在集合 **Authorization** 中配置对应认证方式。请求继承集合认证，凭据保留在本机。
4. 把 Postman 的文件工作目录设为仓库中的 `version2`。部署请求的 **Body → form-data → resources** 已列出主 BPMN 和 `forms/` 下的 37 个表单；如果路径未绑定，重新选择这 **38 个文件**。不要选择 `source/`、`evidence/`、第一版文件或验收快照。不要手动设置 multipart Content-Type，让 Postman 生成 boundary。
5. 在 `version2/java` 启动 Worker（PowerShell，JDK 21+）：

   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hpas.simulation.external-responses=false"
   ```

该参数关闭排班、付款、检验等**外部模拟回话**，以便手动用 Postman 发送。Worker 仍处理自动任务，并发送门诊信函、退款、治疗变更等**内部业务交接消息**。`version1` 的 `hospital.simulation.*` 配置不适用于此版。

使用 Postman 桌面版或能够访问本机端口和文件的本地代理；云端 runner 的 `localhost` 不是你的电脑。

## 最短可操作流程

1. 在 **01 - Setup and referral entry** 中依次发送健康检查、部署、流程定义查询。
2. 发送 **04 - Create HPAS instance**。空的 `referralId`、`patientId` 自动填入唯一值；响应中的 `processInstanceKey` 自动保存。实例从 `Top_Start` 到达 `Top_Catch_Referral`，此时还没有转诊人工任务。
3. 发送 **05 - Referral received**，然后在 [Tasklist](http://localhost:8080/tasklist) 完成 `SP01_CheckDocuments`。使用环境中的患者标识，填入演示工作人员编号，选择资料完整。
4. 在 `SP02_ClinicalReview` 选择 `reviewDecision=accept`，填写必填字段。Worker 请求号源后，发送 **P03 - Scheduling result (slot found)**；到达 `SP03_Catch_Visited` 后发送 **P03 - Clinic visit completed**。
5. 在 Tasklist 完成诊断、同意记录和治疗申请。`SP04_CreateRequest` 必须有 `treatmentPlanId`、`authorisingClinician`、`requiredStartDate`、`resourceType` 等表单必填信息，否则真实 Worker 会让申请返回修改。示例采用 `internal` 资源、`hospital` 经费；审批、临床决定仍通过表单填写。
6. 到达 `SP08_Catch_Lab` 后发送 **P08 - Laboratory result**，在 `SP08_Review` 填写临床决定。示例选择 `fitnessDecision=fit`、`moreCycles=false` 后，主路径可以等待关闭。
7. 主路径的 Worker 可能已经启动门诊信函分支；在 Tasklist 完成其起草、审批、行政处理等任务。等到 `Top_WaitClosure`，再发送 **Close pathway at Top_WaitClosure**。根实例只有在其他活动事件分支也结束后才能完成。

每一步可以用 **04 - Observe current instance** 查询实例、任务、变量和 incident，或在 [Operate](http://localhost:8080/operate) 查看当前节点。上述是人工调试步骤，尚未在本次添加工作中进行集群端到端实测。

**不要对整个集合点击 Run。** 这些请求包含替代结果和独立事件分支，应该按当前等待点逐个选择；并非顺序执行的自动验收套件。

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

集合含 HTTP 状态断言、返回 key 检查、查询数组检查，以及环境和关联键检查。本次检查覆盖 Postman v2.1 schema、全部请求 JSON、38 个部署文件、22 种消息与 BPMN 的对应关系及脚本执行。由于本地 Camunda 8080 未运行，未进行在线接口或完整业务流程验证。

- [Version 2 说明](../README.zh-CN.md)
- [验收计划与变量映射](../docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md)
- [Worker 的实际模拟逻辑](../java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java)
- [Camunda 8.9 创建实例](https://docs.camunda.io/docs/8.9/apis-tools/orchestration-cluster-api-rest/specifications/create-process-instance/)
- [Camunda 8.9 消息关联与缓冲](https://docs.camunda.io/docs/8.9/components/concepts/messages/)

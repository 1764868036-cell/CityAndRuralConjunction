# Postman 接口集合

[English](README.md) | **简体中文**

本目录提供 29 个针对 **Camunda 8.9 Orchestration Cluster REST API** 的请求，适配本仓库的 `1-17.bpmn`。Java 应用是 job worker，没有独立的患者 CRUD HTTP API；请求直接发到 Camunda，默认地址为 `http://localhost:8080`。

## 导入与启动

1. 在 Postman 中点 **Import**，同时选择本目录的 `Hospital_Patient_Pathway.postman_collection.json` 和 `Local_Camunda.postman_environment.json`。
2. 选择环境 **Hospital Pathway - Local Camunda 8.9**。确认 `baseUrl`，不要在末尾添加 `/` 或 `/v2`。
3. 启动本地 Camunda 8.9。集合默认 **No Auth**；若集群开启认证，在集合的 **Authorization** 中设置该集群要求的认证方式。请求继承集合认证，凭据保留在本机。
4. 将 Postman 的文件工作目录设置为仓库中的 `HospitalPatientPathway` 文件夹。在部署请求的 **Body → form-data → resources** 中检查文件；若导入后未绑定，重新多选 `1-17.bpmn` 和根目录全部 **16 个 `.form`**。这些文件必须在同一次部署中上传。Content-Type 由 Postman 自动生成，不要手动填写 multipart boundary。
5. 在 `HospitalPatientPathway/java` 中启动 worker（PowerShell）：

   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hospital.simulation.scenario=none --hospital.simulation.auto-responder-enabled=false"
   ```

   使用桌面版 Postman，或使用能够访问本机地址及部署文件的本地代理。云端 runner 无法通过自己的 `localhost` 访问你的 Camunda。

## 第一次运行

1. 依次发送 **01 - Check cluster topology**、**02 - Deploy BPMN and all 16 forms**、**03 - Find deployed hospital definitions**。
2. 发送 **04 - Create hospital instance**。`referralId`、`patientId` 为空时会生成唯一标识；成功后保存返回的 `processInstanceKey`，供查询请求使用。
3. 打开 [Tasklist](http://localhost:8080/tasklist)，找到该实例的 **Register referral in PAS and check documents**（`R1_RegisterCheck`），填写并提交转诊表单。创建请求已提供虚构演示数据。worker 必须运行，因为表单提交会触发输入校验 listener。
4. 表单完成后，实例到达 `Gateway_ExternalTriggerRouter`。从 **02 - Entry events** 中选择一条消息发送：例如 **P01 - Incoming referral** 进入转诊路径，或 **P15 - Monitoring request** 进入报表路径。
5. 在 Tasklist 中继续处理人工任务；流程等待患者偏好、知情同意或检验结果时，从 **03 - Patient and laboratory responses** 发送对应消息。接受/改期/拒绝、同意/拒绝分别是替代选项，按要测试的分支选择。
6. 使用 **04 - Observe current instance** 查询实例、用户任务、变量及 incident，也可以在 [Operate](http://localhost:8080/operate) 查看流程位置。

**不要直接运行整个 Collection。** 它是交互式调试工具，包含互斥业务分支；不是自动跑通全流程的顺序测试套件。想测试另一个入口时，新建独立实例并先完成其转诊表单。

## 变量与消息

| 变量 | 用途 |
| --- | --- |
| `baseUrl` | Camunda 地址，默认 `http://localhost:8080` |
| `processDefinitionId` | `Process_Hospital_Integrated`，创建最新活动版本 |
| `referralId` | 入口消息和大多数应答的关联键 |
| `patientId` | 检验结果的关联键 |
| `patientName` | 创建实例使用的演示姓名 |
| `incidentReference` | 中断报告及恢复消息使用的同一个业务标识；新中断应改为唯一值 |
| `processInstanceKey` | 创建成功后自动保存，也可手动填写已有实例的 key |
| `today` / `preferredDate` | 集合脚本生成的 UTC 日期及 14 天后的日期，作为请求局部变量 |

新一轮测试前清空环境中的 `referralId` 和 `patientId`，或设置新的唯一值。同一次测试中保持这些值不变；若在 Tasklist 修改了转诊标识，发送消息前同步修改环境值。调试已有实例时，手动填写其真实标识。

当前模型只有普通起始事件 `Start_Tasklist`。**单独发入口消息不会创建实例**；先创建实例、完成表单，再选消息。所有消息名包含模型要求的 `Hospital pathway: ` 前缀。

消息 TTL 为 **900000 毫秒（15 分钟）**，用于等待订阅出现。每次 Send 都生成新的 `messageId`，因此再次点击相当于发布一条新消息；测试同一消息的去重时，应手动固定请求体的 `messageId`。发布返回 HTTP 200 只证明引擎接收了消息，不证明已关联或业务流程已完成。通过实例状态和当前节点确认结果。

人工发送患者应答时关闭定时自动应答。worker 仍会模拟排班、经费、支付等请求的回话；发送患者接受/改期/拒绝前，应检查 worker 的 `hospital.simulation.patient-decision` 配置，避免其模拟应答先被消费。Postman 直接调用 Camunda，也不会经过 `ExternalEventService` 的入口审计记录逻辑。

查询接口存在索引延迟，新建实例或完成任务后暂时查不到时可重新发送。搜索请求每页最多取 100 条（流程定义查询为 20 条）；更多结果需要依据响应的分页游标追加查询。

## 校验与参考

集合包含状态码断言、创建实例及消息发布返回值检查、搜索结果结构检查。交付前已校验 JSON、Postman Collection v2.1 schema、文件引用，以及消息名/关联键与 BPMN 和 Java 目录的一致性。未连接运行中的集群进行端到端验证。

- [当前模型运行约定](../java/RUNTIME_COMPATIBILITY.md)
- [Camunda 8.9：创建实例](https://docs.camunda.io/docs/8.9/apis-tools/orchestration-cluster-api-rest/specifications/create-process-instance/)
- [Camunda 8.9：消息与关联](https://docs.camunda.io/docs/8.9/components/concepts/messages/)
- [Java worker 配置及运行说明](../java/README.zh-CN.md)

旧人工测试文档含历史模型说明；入口语义以当前 BPMN、`HospitalMessages.java` 和本说明为准。

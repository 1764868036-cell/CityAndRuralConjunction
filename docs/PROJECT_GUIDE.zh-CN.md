# 理解项目：医院患者转诊与治疗路径

本文是仓库的阅读地图，说明两个版本的职责、BPMN 与表单/Worker 的连接方式，以及如何正确理解测试结果。提交模型前仍应以对应版本目录中的 BPMN、表单、源码和测试报告为准。

## 1. 项目解决什么问题

系统围绕患者转诊建立一条跨角色的医院服务路径：接收转诊资料、判断是否完整及适宜、审核并预约初诊、安排治疗与资金、记录支付和治疗结果，并处理后续改约、退款、问询、信件、随访、访问审计和系统中断等情况。

这是一个流程协调示例。业务人员在 Tasklist 中处理用户任务；流程通过消息与外部参与者交换结果；自动化节点由 Java Worker 或 REST Connector 执行。外部参与者泳道中的模拟流程用于演示协作应答，不代表真实医院、保险、支付或排班服务已接通。

## 2. 版本关系与规范文件

| | Version 1 | Version 2 |
| --- | --- | --- |
| 目录 | `version1/` | `version2/` |
| BPMN | `1-17.bpmn`，流程 ID `Process_Hospital_Integrated` | `hospital-patient-pathway.bpmn`，流程 ID `Process_HPAS` |
| 设计目标 | 阶段 1–17 的集成详细流程和既有表单/测试基线 | 独立重设计，主图展示 P01–P08，后续分支折叠并可下钻 |
| 表单 | 16 个共享表单服务多个任务 | 37 个表单对应 V2 的人工作业 |
| Java | `version1/java/` | `version2/java/` |

V2 的主模型和表单位于 `version2/` 顶层与 `version2/forms/`。主模型中有 9 个事件子流程，内部任务和泳道在 BPMN 图层中；在 Camunda Modeler 中点击折叠子流程的“+”可下钻。`version2/evidence/acceptance-candidate/` 是单独保存的候选快照，带有不同的模型/表单契约，只用于隔离验证，不是 canonical BPMN，也不应与规范模型混合部署。

早期根目录的模型、表单、交付文件和证据保存在 `archive/legacy-root/`；案例和课程资料保存在 `docs/`。完整位置见[目录地图](REPOSITORY_STRUCTURE.zh-CN.md)。本文代码块中的路径以仓库根目录为基准。对流程或 Worker 做当前改动时，先选定 V1 或 V2，再只在相应版本目录内检查和修改。

## 3. BPMN、Form、Worker 如何协作

1. **BPMN** 决定流程顺序、泳道职责、分支条件、消息等待和异常/事件路径。
2. **Camunda Form** 绑定用户任务。人工填写的字段以流程变量或任务变量供后续网关、消息订阅及 Worker 使用。
3. **Java Job Worker** 订阅 BPMN 服务任务的 job type，处理业务自动化、模拟外部应答和/或持久化。Worker 只会收到变量中实际存在的值；在表达式或关联键引用变量前，必须确保该变量已初始化。
4. **REST Connector** 在 V2 的外部资源可用性节点发出 HTTP 请求。部署时需要 Connector Runtime 能访问配置的 URL secret 和服务地址；环境变量/secret 名称及表达式请以 `version2/hospital-patient-pathway.bpmn` 与 `version2/postman/README.zh-CN.md` 为准。
5. **外部泳道模拟** 通过简单任务/消息来回演示对方应答，帮助本地走通协作路径。它是测试替身，不等价于生产 API、身份系统或真实第三方集成。

部署时应按版本说明一起部署 BPMN 和所引用的表单；避免把不同版本或候选包中的同 ID 资源混入同一部署。Camunda Modeler 的 process application 可能会把应用目录下的 BPMN/Form 一起打包，因此目录中多余副本会导致重复 ID 或错误版本被部署。

## 4. 重点变量与消息关联

V2 的消息与模拟外部响应通常使用 `referralId` 作为 correlation key。它必须在消息订阅建立前存在，并且是字符串或数字。若实例启动时没有初始化 `referralId`，或某表单/Worker 仅写入任务局部变量而未映射到流程变量，表达式求值时就会出现 `NO_VARIABLE_FOUND`，Operate 可能显示 correlation key 为 `NULL`，随之产生 incident。

排查这类问题时按数据链逐步确认：

- 启动入口是否提交了 `referralId`；
- Form 字段绑定、用户任务输出映射是否把值写到正确的流程作用域；
- Worker 消息发布时是否传入同一个值；
- BPMN 消息订阅与 REST Connector 的 correlation key 表达式是否拼写一致。

其他关键数据包括 `patientId`、预约/治疗标识、支付结果和各子流程需要的业务状态。变量名应以具体 BPMN、Form 和 Worker 实现为准，不能仅凭任务标签推断。

## 5. 目录阅读顺序

建议按以下顺序了解项目：

1. 本文与根目录 [README.zh-CN.md](../README.zh-CN.md)：先理解版本边界。
2. 选定版本，打开其 README 和 BPMN：V1 看 `version1/1-17.bpmn`；V2 看 `version2/hospital-patient-pathway.bpmn`。
3. 查表单映射和表单文件：V1 查看 `version1/Form_Task_Mapping.csv`；V2 查看 `version2/forms/`，并以 BPMN 的 form reference 为准。
4. 查 Worker job type 和实现：V1 查看 `version1/java/README.md`、`WORKER_MAP.md`；V2 查看 `version2/java/src/main/java/` 与 `version2/tools/`。V2 REST 与 Postman 操作见 `version2/postman/README.zh-CN.md`。
5. 最后读测试计划和结果：V1 看 `version1/TEST_RESULTS.md`；V2 看 `version2/docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md`、`version2/TEST_RESULTS_VERSION2.zh-CN.md` 及 `version2/evidence/`。

## 6. 测试结果应该怎样理解

- **V1**：最近的完整 Maven 测试运行是 50 项通过、0 失败；这是该次源码和环境的测试结果，不是对 V2 的结论。
- **V2 历史验收快照**：报告记录 86 项中 74 项通过、12 项失败。失败标准保留在原验收计划中；401/401 BPMN 路由覆盖不能替代业务断言。
- **API 完成任务**：证明引擎接受了任务完成命令，不必然证明 Tasklist 浏览器表单正确呈现、字段可选、权限正确或数据映射完整。
- **浏览器 E2E**：已记录的 SP05 happy path 是 12 次网页提交、9 种表单且实例完成；不覆盖所有分支和权限隔离。
- **V2 接受候选快照**：需通过 `-Dacceptance.candidate=true` 显式切换测试输入。它用于候选版合同验证，不能用其通过数替代 canonical 模型的测试或部署结果。

当前 V2 默认模型契约测试针对 canonical BPMN 仍有已知失败，原因是候选 Worker/契约与 canonical BPMN 不完全一致。执行时要区分默认测试和候选测试；对候选包运行可以使用 `./mvnw.cmd -Dacceptance.candidate=true test`，并且结果只说明候选快照，不代表规范主图已验证通过。

复测时要记录所用 BPMN/Form 快照、集群版本、测试 profile、浏览器或 API 测试方式、通过/失败数及 incident。不要把静态模型检查、引擎路由覆盖、接口测试、浏览器表单测试和完整验收混为一个“通过率”。

## 7. 常用命令

从仓库根目录执行：

```powershell
# V2 BPMN 包、资源映射与语义检查
python version2/tools/validate_bundle.py
python version2/tools/check_semantics.py version2/hospital-patient-pathway.bpmn

# V1 测试
Set-Location version1/java
./mvnw.cmd test

# V2 基础测试
Set-Location ../../version2/java
./mvnw.cmd test

# V2 冻结验收快照（需要可重置的 Camunda 8.9 测试环境）
./mvnw.cmd -Pacceptance test
```

V2 验收 profile 会运行更广的冻结快照测试，原验收标准中的失败会继续报告为失败。不要只看 Maven 的总体结果；应结合 `version2/TEST_RESULTS_VERSION2.zh-CN.md` 和机器可读证据判断失败项及范围。

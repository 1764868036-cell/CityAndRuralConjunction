# 人工测试全流程（Manual Test Walkthrough）

**English** | [简体中文](MANUAL_TEST_GUIDE.zh-CN.md)

一次部署之后，人能不能把整条路径点完？哪些步骤必须等外部消息、哪些必须有人在 Tasklist 干活、每步要填什么/发什么。
本文所有元素 id、消息名、关联键、字段名都取自 [`1-17.bpmn`](1-17.bpmn) 原文；命令都在本机 c8run 上实测过（结果见第 10 节）。

---

## 1. 前提：三样东西必须在位

| 组件 | 检查方式 | 命令 |
| --- | --- | --- |
| 集群 | gRPC 26500 / REST 8080 在听 | `netstat -ano \| findstr "26500 8080"` |
| 模型 + 15 个 form | 部署返回 200 | 见 [`README.zh-CN.md`](README.zh-CN.md) 的部署命令 |
| worker 应用（演示必须） | 进程在跑 | `jps -l \| findstr HospitalPathway` |

两个界面：**Tasklist** <http://localhost:8080/tasklist>（人工任务）、**Operate** <http://localhost:8080/operate>（看实例、incident、当前停在哪个元素）。

最快的健康检查（尤其**机器/集群重启之后**）：`POST /v2/jobs/search` 带 `{"filter":{"state":"CREATED"}}` 必须返回 **0** 条。
只要列出作业，就说明没有 worker 在消费 —— 应用没跑（或连不上集群），所有 `service` / `send` 步骤都会停住。
重启不会丢数据（H2 保留，部署与实例都还在）。

## 2. 演示模式：只要把应用跑起来

| | 演示模式（默认配置） |
| --- | --- |
| worker 应用 | **运行**：`mvn spring-boot:run` |
| 自动化步骤（service / send task） | 应用里的 worker 自动完成 |
| 患者 / 实验室 / 外部机构的应答 | 应用自动代发：有请求的（补件、档期、外部服务、保险、付款、退款、信函）由对应 worker 直接发；**没有请求的**四条（患者偏好、患者同意、检验结果、服务恢复）由自动应答每 10 秒发一次（`auto-responder-enabled=true`，默认开启） |
| **人需要做的** | 只在 Tasklist 里完成任务、把表单里该选的选对 |

⚠️ **实例的 id 请用默认值**：自动应答固定用 `hospital.simulation.default-referral-id` / `default-patient-id`
（即 `REF-1001` / `PAT-1001`）。消息按「名字 **+** 关联键」匹配，所以起实例时带上这两个 id（见第 3 节），
后续所有等待点都会被自动应答命中 —— **演示过程中不需要手工发任何消息**。

**关键前提**：service / send 任务是 **job**，只有 worker（或 REST）能完成它们，Tasklist 里点不到；
所以演示时应用必须开着。要单独验证某一步、或看不跑应用会发生什么，见附录 A。

## 3. 启动实例：在界面上点一下就行（不用发消息）

模型唯一的**普通起始事件**就是流程 1 的入口 `Start_Tasklist`（"Start referral in Tasklist"，对应案例 1.1 由医务秘书受理转诊），
所以 Operate 或 Tasklist 里的「Start instance / Start process」不指定 `startInstructions` 就会从流程 1 开始。

1. Operate → Processes → 选中 `Hospital Patient Referral…` → **Start instance**（Tasklist 的 Processes 页同样可以）
2. 变量框里填（**id 用默认值**，自动应答才对得上）：

```json
{"referralId":"REF-1001","patientId":"PAT-1001","patient_name":"Alex Morgan"}
```

3. 提交后 Tasklist 里第一条任务就是 `R1_RegisterCheck`（assignee `medical-secretary`），点进去开始走流程。

其余入口都是**消息起始事件**（`Incoming referral package`、`Next treatment cycle due`、P9–P16 各一条）；
要看 P8 那条卫星路径，用应用自带的场景（第 4 节末尾）或附录 A 的消息命令即可 —— 但那是支线，演示主路径不需要。

（想要命令行启动的方式，或要发消息起 P8 等支线，见附录 A —— 演示主路径都不需要。）

## 4. 主路径逐步（正常路径）

进入 Tasklist 后：任务若按「Assigned to me」看不到，切到 **All open tasks**，必要时先 **Claim** 再 **Complete**。
下表「谁」= 模型里 `assignmentDefinition` 的 assignee / candidateGroups。

| # | 位置 / 元素 | 谁 | 做什么、关键点 |
| --- | --- | --- | --- |
| 1 | 阶段 1 `Phase_ReceiveReferral` · `R1_RegisterCheck` | medical-secretary | 表单 `01_receive_referral`；**`documentsCompleteChoice` 选 Yes** → 阶段直接结束进入阶段 2；选 No/不填 → 进补件循环（第 5.1 节） |
| 2 | 阶段 2 `Phase_ReviewReferral` · `R2_Review` | consultant | 表单 `02_review_referral`；这里问的是“临床是否适合”，不是是否紧急：适合但不紧急也选 **Yes**，然后在 `R2_Route` 选择 Routine；只有临床不适合才选 No 并结束 |
| 3 | 网关 `Gateway_ReferralSuitable` | — | 读 `referralSuitable = true` → 进入阶段 3 |
| 4 | 阶段 3 `Phase_InitialConsultation` | 见下 | ① 等消息 `Patient appointment preference`（**应用自动应答**）② 自动 `request-consultation-slot` 发 `Initial consultation slot request` ③ 等 `Initial consultation slot availability`（**worker 自动发**）④ `R3_RecordAvailability`（booking-clerk，表单 `03_initial_consultation`，**`availability` 选 available**）⑤ 自动发 `Proposed consultation appointment` ⑥ 事件网关 `R3_WaitPatientDecision` 等三个消息之一（**均由应用自动应答**）：`Patient appointment response`（接受→`R3_Booked`）/ `Alternative consultation date`（改期→回到 ② 重新找档期）/ `Patient declines consultation`（拒绝→`R3_Declined`，阶段结束） |
| 5 | 网关 `Gateway_PatientConfirmed` | — | 读 `appointmentAccepted = true`（由上面两个 catch 事件写入）→ 进入阶段 4 |
| 6 | 阶段 4 `Phase_DiagnosisConsent` | consultant / clinical-team | `R4_Assess` → `R4_Explain` → 等消息 `Patient consent decision`（**应用自动应答，payload 带 `informedConsent: true`**）→ 网关 `R4_Consent` → `R4_RecordConsent` |
| 7 | 网关 `Gateway_ConsentRecorded` | — | 读 `informedConsent = true` → 进入阶段 5 |
| 8 | 阶段 5 `Phase_ArrangeTreatment` | treatment-booking-team | `R5_ReviewPlan` → 自动 `request-treatment-service` 发 `Treatment or external service request` → 等 `Treatment or external service availability`（**worker 自动发，带 `allServicesAvailable: true`**）→ `R5_ConfirmTreatment` |
| 9 | 阶段 6 `Phase_VerifyCost` | finance | `R6_ClassifyFunding`（**`externalAuthorisationRequiredChoice`、`paymentRequiredChoice` 决定后面走不走授权/付款**）→ 若需授权：自动 `request-funding-authorisation` 发 `Funding authorisation request` → 等 `Funding authorisation decision`（**worker 自动发，带 `fundingAuthorised: true`**）→ `R6_RecordAllocation`（**`fundingApprovedChoice` 选 Yes**） |
| 10 | 网关 `Gateway_FundingApproved` → `Gateway_PaymentRequired` | — | `fundingApproved = true`；`paymentRequired = true` 才进阶段 7（否则直接跳 `Confirm_Treatment_Booking`） |
| 11 | 阶段 7 `Phase_ProcessPayment` | finance | `R7_CalculateCharge` → 自动 `request-payment` 发 `Secure payment request` → 等 `Payment result from provider`（**worker 自动发，带 `paymentStatus: "paid"`**）→ 网关 `R7_PaymentPaid` → `R7_RecordPayment` |
| 12 | 网关 `Gateway_PaymentConfirmed` | — | `paymentStatus = "paid"` → `Confirm_Treatment_Booking`（人工，表单 `05_treatment_services`）→ `Deliver_Treatment_Cycle`（人工） |
| 13 | 阶段 8 `Phase_ContinueTreatment` · `R8_ClinicalReview` | clinical-team | 表单 `08_continue_treatment`；**`clinicalDecision`** 填 `continue` / `change` / `delay` / `stop` |
| 14 | 网关 `Gateway_ClinicalDecision` | — | `continue`/`change` → 回到阶段 5（下一个治疗周期，循环）；`delay` → 定时器 `= reassessmentDelay`；其余（含 stop）→ 默认走 `Finance_RefundReview`（退款分支） |

循环：每完成一个治疗周期就重复 8 → 13 → 14，直到 `clinicalDecision` 走到 `delay`（等复评）或 `stop`（退款）。

### 4.1 阶段 1 的补件循环（最容易被当成"卡住"）

```
R1_RegisterCheck → R1_Complete「Required information complete?」
   ├ Yes（= documentsComplete = true）→ R1_Ready（阶段结束）
   └ 默认 → R1_RequestDocs（job send-referral-documents-request，发「Requested referral documents」）
            → R1_ReceiveDocs（等消息「Requested referral documents」，关联键 = referralId）
            → R1_UpdateReferral（人工，medical-secretary，再判一次）→ 回到 R1_Complete
```

## 5. 分支速查：每个网关读什么、从哪来、选什么

「来源」= 该变量的产生方式：**表单** = 人在 Tasklist 填的字段（模型里的 ioMapping 会自动换算成变量）；**消息** = 必须由外部消息的 payload 带进来；**模型** = 模型自己写。

| 网关（阶段） | 变量 | 来源 | 取值 → 效果 |
| --- | --- | --- | --- |
| `R1_Complete`（1） | `documentsComplete` | 表单 `01`：`documentsCompleteChoice = "yes"` | yes → 阶段结束；否则 → 补件循环 |
| `R2_Suitable`（2） | `referralSuitable` | 表单 `02`：`referralSuitableChoice = "yes"` | yes → 排优先级；no → 拒绝并回话 |
| `Gateway_ReferralSuitable` | 同上 | 同上 | `referralSuitable` 由阶段 2 子流程输出映射传出；临床适合（Yes）→ 阶段 3，Urgent / Routine 均继续；临床不适合（No）→ 流程结束（`End_ReferralNotSuitable`） |
| `R3_SlotAvailable`（3） | `slotAvailable` | 表单 `03`：`availability = "available"` | available → 发预约；否则 → 等待重试 |
| `R3_WaitPatientDecision`（3） | 事件网关 | — | 收到 `Patient appointment response` / `Alternative consultation date` / `Patient declines consultation` 三个之一 |
| `Gateway_PatientConfirmed` | `appointmentAccepted` | 模型（两个 catch 事件写 true/false） | true → 阶段 4；否则流程结束（`End_PatientDeclined`） |
| `R4_Consent`（4） | `informedConsent` | **消息** `Patient consent decision` 的 payload | true → 记录同意；否则记录拒绝 → 流程结束 |
| `Gateway_ConsentRecorded` | 同上 | 同上 | true → 阶段 5；否则流程结束 |
| `R5_AllAvailable`（5） | `allServicesAvailable` | **消息** `Treatment or external service availability` 的 payload | true → 确认预订；否则 → 等待重试（定时器 `= treatmentServiceRetryDelay`） |
| `R6_AuthNeeded`（6） | `externalAuthorisationRequired` | 表单 `06`：`externalAuthorisationRequiredChoice = "yes"` | yes → 请求保险授权；否则直接记账 |
| `R6_AuthApproved`（6） | `fundingAuthorised` | **消息** `Funding authorisation decision` 的 payload | true → 记账；否则 → `R6_ResolveGap`（资金缺口） |
| `Gateway_FundingApproved` | `fundingApproved` | 表单 `06`：`fundingApprovedChoice = "yes"`（`R6_ResolveGap` 里固定写 false） | true → 看是否需要预付款；否则流程结束（`End_FundingHold`） |
| `Gateway_PaymentRequired` | `paymentRequired` | 表单 `06`：`paymentRequiredChoice = "yes"` | yes → 阶段 7；否 → 直接去确认治疗预订 |
| `R7_PaymentPaid`（7） | `paymentStatus` | **消息** `Payment result from provider` 的 payload（`"paid"`）或 `R7_RecordPayment` 表单写入 | paid → 记录付款；否则 → 催付/重试分支 |
| `R7_RetryAllowed`（7） | `retryApproved` | 表单 `07`：`retryApprovedChoice = "yes"` | yes → 查重复扣款（job `check-payment-idempotency`）；否则 → 标记调查 |
| `R7_NoChargeFound`（7） | `priorChargeFound` | worker `check-payment-idempotency` 完成作业时写入 | false → 重发付款请求；true → 标记调查 |
| `R7_ReconciledPaid`（7） | `providerConfirmsPaid` | 表单 `07`：`providerConfirmsPaidChoice = "yes"` | true → 记录付款；否则 → 付款未结、治疗暂停 |
| `Gateway_PaymentConfirmed` | `paymentStatus` | 同上 | `"paid"` → 确认治疗预订；否则 `End_PaymentHold` |
| `Gateway_ClinicalDecision`（8） | `clinicalDecision` | 表单 `08` | `continue`/`change` → 回阶段 5；`delay` → 定时器；默认（stop）→ 退款分支 |
| `Gateway_RefundAuthorized` | `refundAuthorised` | 表单 `07`：`refundAuthorisedChoice = "yes"` | yes → 自动发退款请求 → 等 `Refund result from provider` → `Finance_RecordRefund`；否则结束 |
| P8 `P8_Gateway_Fit` / `P8_Gateway_Action` | `fitToContinue` / `action` | 表单 `08`（`yes` / `change`） | 决定下一周期继续或推迟/改计划 |
| P9 `P9_Gateway_Type` | `requestStatus` | 表单 `09`（`approved` / `urgent` / 其它） | 决定排期/紧急推迟/不处理 |
| P9 `P9_Gateway_Costs` | `affectsChargeOrFunding` | 表单 `09`（`yes`） | yes → 财务复核 |
| P10 `P10_Gateway_Decide` | `caseDecision` | 表单 `10` 的 `P10_Task_Record`（`re-book` / `clinical-review` / `notify-referrer`） | 选择改期、临床复核或通知转诊方；之后汇入付款判断并结束 |
| P10 `P10_Gateway_Paid` | `paymentReceived` | 表单 `10`（`yes`） | yes → 财务决定退款/转账 |
| P11 `P11_Gateway_Outcome` | `refundOutcome` | 表单 `11`（`refund` / `transfer` / 其它=拒绝） | 决定退款、转账或拒绝 |
| P12 三个网关 | `urgentConcern`、`enquiryCategory` | 表单 `12`（`yes`；`admin-simple` / `admin-other` / `finance` / 其它） | 决定加急标记、转团队、转财务 |
| P13 `P13_Gateway_0utxesn` | `consultantApproved` | 表单 `13`（`yes`） | yes → 发信函给医务秘书；否则回到复核 |
| P13 `P13_Gateway_1uxwgi9` | `noSuspectedClinicalError` | 表单 `13`（`yes`） | yes → 分发信函（写 `letterComplete = true`）；否则退回顾问 |
| P13 三个到期网关 | `letterComplete` | 模型（草稿写 false、分发写 true） | false → 逾期提醒/升级（7 天 / 1 个月 / 3 个月） |
| P14 `P14_Gateway_Slot` | `slotAvailable` | 表单 `14`（`yes`） | yes → 自动预订随访；否则转人工关注 |
| P16 `P16_Gateway_Access` | `accessAuthorised` | 表单 `16`（`yes`） | yes → 授权权限；否则直接进审计 |

## 6. 消息清单：谁发、关联键、payload 里必须有什么

**每一行的发送方都是应用** —— 对应 worker 或自动应答；演示中不需要手工发布任何消息。
（需要手工发的时候用附录 A 的命令，例如不跑应用单独验证某一步。）

| 消息名 | 谁发 | 关联键 | payload 里必须有的关键变量 |
| --- | --- | --- | --- |
| `Incoming referral package` | 外部（转诊方） | `referralId` | `referralId`、`patientId`、`patient_name` |
| `Next treatment cycle due` | 医院内部/系统（下一轮治疗到期，启动流程 8） | 无关联键 | `referralId`、`patientId`、`cycleNumber`（P8 后续要用 `patientId`） |
| `Requested referral documents` | 应用（`send-referral-documents-request` 的 worker） | `referralId` | `receivedDocumentReferences`、`referralUpdateNotes` |
| `Referral review outcome` | 应用（`send-referral-outcome`） | `referralId` | `referralOutcome` |
| `Patient appointment preference` | 患者 | `referralId` | `preferredDate` 等（可选） |
| `Initial consultation slot request` | 应用（`request-consultation-slot`） | `referralId` | — |
| `Initial consultation slot availability` | 外部（排班） | `referralId` | `availability`、`date_of_appointment`、`appointmentTime` |
| `Proposed consultation appointment` | 应用（`send-appointment-confirmation`） | `referralId` | — |
| `Patient appointment response` | 患者 | `referralId` | （模型自己写 `appointmentAccepted = true`） |
| `Alternative consultation date` | 患者 | `referralId` | `alternativeAppointmentDate` |
| `Patient declines consultation` | 患者 | `referralId` | （模型写 `appointmentAccepted = false`） |
| `Patient consent decision` | 患者 | `referralId` | **`informedConsent: true`**（false/缺 → 走拒绝分支） |
| `Treatment or external service request` | 应用（`request-treatment-service`） | `referralId` | — |
| `Treatment or external service availability` | 外部（检验/治疗） | `referralId` | **`allServicesAvailable: true`** |
| `Funding authorisation request` | 应用（`request-funding-authorisation`） | `referralId` | — |
| `Funding authorisation decision` | 外部（保险/资金方） | `referralId` | **`fundingAuthorised: true`** |
| `Secure payment request` | 应用（`request-payment`） | `referralId` | — |
| `Payment result from provider` | 外部（支付服务商） | `referralId` | **`paymentStatus: "paid"`** |
| `Authorised refund request` | 应用（`submit-authorised-refund`） | `referralId` | — |
| `Refund result from provider` | 外部（支付服务商） | `referralId` | `refundStatus`、`refundedAmount` |
| `TestResultsMessage` | **应用自动应答**（扮演实验室） | **`patientId`** | `fitToContinue: "yes"` |
| `LetterFromDoctor` / `LetterBackForRechecking` / `MessageFromDoctor` / `ReportFromDoctor` | 应用（信函各 worker） | **`patientId`** | 信函处理结果 |
| `TreatmentChangeRequestMessage` | 外部 | 无关联键 | `referralId`、`patientId`（建议带上） |
| `AppointmentCancellationReportMessage` | 外部 | 无关联键 | 同上 |
| `RefundCaseReceivedMessage` | 外部 | 无关联键 | 同上 |
| `PatientQuestionMessage` | 患者 | 无关联键 | 同上 |
| `ClinicVisitCompletedMessage` | 外部（门诊） | **`patientId`** | `patientId` |
| `FollowUpRequestedMessage` | 顾问 | 无关联键 | `referralId`、`patientId` |
| `Monitoring run or report request` | 外部 | 无关联键 | — |
| `Staff identity or access change request` | 外部 | 无关联键 | `targetStaffUserId`、`requestedRole` |
| `System or external-service interruption` | 外部 | 无关联键 | `incidentReference`、`affectedService` |
| `System or external service restored` | **应用自动应答**（扮演外部系统） | `incidentReference` | `incidentReference`（与上面一致） |

演示时这张表**不需要你操作**：每一行的发送方都是应用（对应 worker 或自动应答）。只有在调试、或要起 P8/P9… 支线时才需要手工发消息，命令模板见**附录 A（第 7 节）**。

## 7. 附录 A：手工调试命令（演示流程不需要）

```bash
# 启动实例（等价于界面上的 Start instance；用默认 id，自动应答才对得上）
curl -s -X POST http://localhost:8080/v2/process-instances -H "Content-Type: application/json" \
  -d '{"processDefinitionId":"Process_Hospital_Merged","variables":{"referralId":"REF-1001","patientId":"PAT-1001","patient_name":"Alex Morgan"}}'

# 发一条消息（模板：换 name / correlationKey / variables 即可；无关联键的消息不要传 correlationKey）
curl -s -X POST http://localhost:8080/v2/messages/publication -H "Content-Type: application/json" \
  -d '{"name":"Next treatment cycle due","variables":{"referralId":"REF-1001","patientId":"PAT-1001","cycleNumber":2}}'
# 想「预答复」：加上 "timeToLive": 600000（毫秒），引擎会把消息缓冲到订阅出现时立即关联

# 不跑应用时，手工完成一个 job
curl -s -X POST http://localhost:8080/v2/jobs/activation -H "Content-Type: application/json" \
  -d '{"type":"send-referral-documents-request","maxJobsToActivate":1,"timeout":60000,"worker":"manual-tester"}'
curl -s -X POST http://localhost:8080/v2/jobs/<jobKey>/completion -H "Content-Type: application/json" -d '{"variables":{}}'
# ⚠️ send task 的语义是「对外发消息」：手工完成 job 不会自动把消息发出去，接收端仍在等

# 查询：活动实例 / 某实例走过的元素 / 开放任务 / incident
curl -s -X POST http://localhost:8080/v2/process-instances/search -H "Content-Type: application/json" -d '{"filter":{"state":"ACTIVE"}}'
curl -s -X POST http://localhost:8080/v2/element-instances/search -H "Content-Type: application/json" -d '{"filter":{"processInstanceKey":"<实例key>"}}'
curl -s -X POST http://localhost:8080/v2/user-tasks/search -H "Content-Type: application/json" -d '{"filter":{"state":"CREATED"}}'
curl -s -X POST http://localhost:8080/v2/incidents/search -H "Content-Type: application/json" -d '{}'
```

PowerShell 里 `curl` 是 `Invoke-WebRequest` 别名，必须写 `curl.exe`，且 JSON 里的双引号要转义（`\"`）。

## 8. 卡点排查

1. Operate → Processes → 勾 **Incidents** → 打开实例 → 看当前元素和 Incident 信息。
2. REST 快速定位（都在本机实测可用）：

```bash
curl -s -X POST http://localhost:8080/v2/incidents/search -H "Content-Type: application/json" -d '{}'
curl -s -X POST http://localhost:8080/v2/element-instances/search -H "Content-Type: application/json" -d '{"filter":{"processInstanceKey":"<实例key>"}}'
curl -s -X POST http://localhost:8080/v2/user-tasks/search -H "Content-Type: application/json" -d '{}'
```

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 停在人工任务、无 incident | 正常等待 | 去 Tasklist 完成任务（表单里把该选的选对） |
| `EXTRACT_VALUE_ERROR … correlation key … NULL` | 实例缺关联键变量（常见：`patientId` / `referralId` 没随启动传入） | 取消重起并带上变量（关联键是订阅创建时算的，补数据只能靠 `modify` + retry） |
| incident：job 类型无人订阅 | worker 应用没跑或没订阅该 job type | 启动应用后对 incident 点 Retry |
| 实例走到某网关后直接结束 | 走了默认出边（变量没被设为条件要求的真值） | 对照第 5 节检查变量的来源与取值 |

## 9. 已知的模型缺口（人工测试会踩到）

| 现象 | 原因（实测） | 绕法 |
| --- | --- | --- |
| P10 的后续动作选项看不到 | `caseDecision` 已加入表单 `10` 的 `P10_Task_Record`，并通过 BPMN 输入/输出映射传给 `P10_Gateway_Decide` | 重新部署更新后的 BPMN 与表单；选择 `re-book`、`clinical-review` 或 `notify-referrer` |
| 阶段 4/5/6/7 的「Yes 分支」在表单里找不到对应开关 | `informedConsent`、`allServicesAvailable`、`fundingAuthorised`、`priorChargeFound` **不是表单字段**，必须由外部应答带来 | 演示模式下应用会自动带上（自动应答 / 对应 worker）；只有手工发消息时才需要自己写进 payload（附录 A） |
| P8/P13 等待点直接 incident | `patientId` 只由外部传入，模型自己不产生（5 处关联键都读它） | 起实例时带 `patientId`（用默认值 `PAT-1001`，自动应答才对得上） |
| 阶段 1 灌完表单不往下走 | `documentsCompleteChoice` 没选 Yes → 补件循环（模型设计如此） | 表单里选 Yes |
| （已修复）以前用 `Start instance` 起出来的实例落进流程 8 | 当时顶层唯一的普通起始事件是 `P8_Start_CycleDue` | 现在唯一的普通起始事件是流程 1 的 `Start_Tasklist`；P8 改为消息 `Next treatment cycle due` 触发（见 `README.zh-CN.md` 模型修复第 7 行） |
| 实例停在 `R3_PatientPreference` / `R4_ReceiveConsentDecision` | 患者侧四条消息由自动应答发出，但它只用配置里的 `REF-1001` / `PAT-1001`，实例 id 与默认值不一致时匹配不上 | 起实例时用默认 id 即可自动推进（见第 3 节） |
| 流程 8 停在 `P8_Catch_Results` 不动 | 它在等消息 `TestResultsMessage`（关联键 `= patientId`）：自动应答每 10 秒会发，但用的是**默认 id `PAT-1001`**，实例里的 `patientId` 与它不一致就匹配不上（消息按「名字 + 关联键」匹配） | 起实例时用默认 id（`PAT-1001`）→ 自动推进；若已用别的 id，取消重起即可（或用附录 A 的命令手工发一条带该 id 的消息） |
| 等待 vs incident 分不清 | 实例无 `patientId` 时，订阅创建那一刻就直接 incident（`EXTRACT_VALUE_ERROR`），不是等消息 | Operate 里带红 ❗ = incident（要修数据）；只是停着 = 正常等消息（等自动应答或支线没触发） |

## 10. 本次实测记录

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 普通 Start instance 落到流程 1 | `POST /v2/process-instances`（不带 `startInstructions`） | HTTP 200，元素轨迹 `Start_Tasklist → Phase_ReceiveReferral → R1_RegisterCheck` |
| P8 的消息入口 | `POST /v2/messages/publication`，`Next treatment cycle due` | HTTP 200，新实例轨迹 `P8_Start_CycleDue → P8_Task_Review` |
| 部署模型 + 15 表单 | `POST /v2/deployments` | HTTP 200，`Process_Hospital_Merged` + 15 form |
| 发消息通道 | `POST /v2/messages/publication` | HTTP 200（返回 `messageKey`） |
| 手工完成作业通道 | `POST /v2/jobs/activation` | HTTP 200（`{"jobs":[]}`） |
| 任务查询通道 | `POST /v2/user-tasks/search` | HTTP 200 |
| incident / 元素实例查询 | `POST /v2/incidents/search`、`/v2/element-instances/search` | HTTP 200（查到了你那条 P8_Catch_Results 的 incident） |
| worker 工程自测 | `mvn -o test` | 12/12 通过（7 模型一致性 + 5 真实集群） |
| 自动应答默认开启 | `mvn spring-boot:run`（不带任何参数）+ 手工完成 `P8_Task_Review` | 实例**没有收到任何手工消息**就穿过 `P8_Catch_Results`：`P8_Task_Review COMPLETED 15:22:52 → P8_Catch_Results COMPLETED → P8_Task_Evaluate ACTIVE`，incident 0 |
| 默认值改为 `true` 后整套测试仍绿 | `mvn -o test` | 12/12 通过（`HospitalPathwayProcessTest` 里显式设 `auto-responder-enabled=false` 以保证确定性） |
| 全流程端到端走查 | `python _analysis/walkthrough_driver.py`（见第 11 节） | 18 个人工任务 + 应用接住全部外部等待 → 实例 `COMPLETED`（`End_TreatmentStopped`），≈86 秒，incident 0 |
| 流程 9–16 的消息启动 | `python _analysis/satellite_demo.py all`（见第 12.1 节） | 九个实例都落在模型规定的位置（p15 约 0.15 秒自行跑完） |
| 流程 13 的三个定时器 | `python _analysis/timer_demo.py deploy` + `p13`（见第 12.2 节） | `Wait 7 days` → `Wait 23 days` → `Wait 2 months` 全部触发，升级链走到高级复核 |
| 阶段 8 的复评定时器 | `HOSPITAL_CLINICAL_DECISION=delay HOSPITAL_REASSESSMENT_DELAY=PT20S python _analysis/walkthrough_driver.py --wait 115`（见第 12.3 节） | `Timer_Reassessment` 20.3 秒后触发，流程重新回到流程 8 |

## 11. 自动走查：一条实例从流程 1 走到结束

`_analysis/walkthrough_driver.py` 用 REST 驱动一条真实实例：**只完成人工任务**（带合理的表单值），
其余全部交给运行中的应用（job worker + 每 10 秒的自动应答）。这是「整条路径跑得通」最快的证明。

```bash
cd _analysis
python walkthrough_driver.py                 # 新建实例（默认 id）并走完
python walkthrough_driver.py 2251799...      # 接管一条正在运行的实例
```

实测（2026-09-27，集群在跑、应用在跑、模型已部署）：

| 项目 | 结果 |
| --- | --- |
| 脚本完成的人工任务 | **18** 个：`R1_RegisterCheck` → … → `Finance_RecordRefund` |
| 用时 | 15:31:26 → 15:32:52（≈86 秒；每处患者/检验等待要等一个 10 秒应答周期） |
| 最终状态 | `COMPLETED`，末元素 `End_TreatmentStopped` |
| incident | **0** |

所有不归人管的等待都由应用自动接住，全程**没有手工发过一条消息**：
`Patient appointment preference` → `Initial consultation slot availability` → `Patient appointment response`（阶段 3）、
`Patient consent decision`（阶段 4）、`Treatment or external service availability`（阶段 5）、
`Funding authorisation decision`（阶段 6）、`Payment result from provider`（阶段 7）、
`Refund result from provider`（退款分支）。完整日志：`_analysis/walkthrough-run-full-path.txt`。

注意

- 脚本用**默认 id** 起实例，自动应答才对得上（见第 2 节）；
- `R8_ClinicalReview` 填 `clinicalDecision = "stop"` 才能让实例结束；想演示「下一周期」就改成 `continue`（会回到阶段 5 循环）；
- `--only R5_ReviewPlan,R8_ClinicalReview` 只点这些任务、其它留给人；环境变量 `HOSPITAL_CLINICAL_DECISION` /
  `HOSPITAL_REASSESSMENT_DELAY` 不改文件就能换阶段 8 的走向（见第 12.3 节）；
- 驱动器发的每个变量都能在任务表单里填出来：`_analysis/check_form_inputs.py` 会把驱动的变量表与模型的
  `formDefinition` 引用、15 个 `.form` 逐个核对，结果为**0 处不匹配**（表单产生不了的变量会单独列出，并标注它来自消息、worker
  还是模型的 ioMapping）—— 也就是说在 Tasklist 里点出来的分支决定与这次自动走查完全一致；
- 它只动自己那条实例，所以你同时手动演示互不影响。

## 12. 演示卫星流程（消息启动）与定时器

### 12.1 一条消息 = 一个卫星流程

除 `Start_Tasklist` 之外的每个入口都是**消息**起始事件，所以发一条消息就会起一个独立实例跑那条流程。
`_analysis/satellite_demo.py` 就是干这个的，并会报告实例停在哪儿：

```bash
cd _analysis
python satellite_demo.py --list                 # 列出全部启动消息
python satellite_demo.py p15                    # 发一条（报告后取消实例）
python satellite_demo.py p13 --keep             # 发一条并保留实例，留给人点
python satellite_demo.py all                    # 九条一起发，输出一张表
```

实测（2026-09-27，模型已部署、应用在跑；「停靠」= 实例等待的第一个人工任务）：

| 用例 | 启动消息 | 停靠在 | 说明 |
| --- | --- | --- | --- |
| `p9` | `TreatmentChangeRequestMessage` | `P9_Task_AdjustSchedule` | `requestStatus: "approved"` 走「正式批准」分支 |
| `p10` | `AppointmentCancellationReportMessage` | `P10_Task_Record` | `caseDecision` 选择改期、临床复核或通知；随后按 `paymentReceived` 进入财务决定或结束 |
| `p11` | `RefundCaseReceivedMessage` | `P11_Task_Decide` | `refundOutcome` 决定退款 / 转账 / 拒绝 |
| `p12` | `PatientQuestionMessage` | `P12_Activity_13m3bda` | `enquiryCategory` 决定转给行政 / 财务 / 临床 |
| `p13` | `ClinicVisitCompletedMessage` | `P13_Activity_0az5u78` **和** `P13_Event_1o398fk`（Wait 7 days） | 信函工作与定时器追办**并行**两条分支 |
| `p14` | `FollowUpRequestedMessage` | `P14_Task_Receive` | `slotAvailable: "yes"` 会自动订随访预约 |
| `p15` | `Monitoring run or report request` | - | **全自动**：采集 → 生成 → 发布，约 **0.15 秒** `COMPLETED`，无人工任务 |
| `p16-access` | `Staff identity or access change request` | `P16_Task_Authenticate` | 之后授权 + 审计日志 |
| `p16-interruption` | `System or external-service interruption` | `P16_Task_RecordInterruption` | payload 带上 `incidentReference: "INC-1001"`，自动应答的「服务恢复」才对得上 |

启动消息**不要传** `correlationKey`（它就是用来创建实例的）。不加 `--keep` 时脚本报告完会把实例取消，所以演示中可反复发。

### 12.2 流程 13 的三个定时器（7 天 / 23 天 / 2 个月）

它们是**字面量**时长（`P7D`、`P23D`、`P2M`），要当场看到触发就得用一份缩短时长的模型副本。
`_analysis/timer_demo.py` 会把副本写在 **bundle 之外**（`bpmn/_timer-demo/1-17-fast-timers.bpmn`），
既不影响「目录里只有一个 `.bpmn`」的守卫，也不会被 Modeler 的流程应用一起部署：

```bash
cd _analysis
python timer_demo.py build --p7d PT45S --p23d PT45S --p2m PT45S   # 默认 PT15S
python timer_demo.py deploy
python satellite_demo.py p13 --keep        # 起流程 13
#   Tasklist 里点掉「Draft the clinic letter」—— 必须在第一个定时器触发之前（它会写 letterComplete = false）
python timer_demo.py restore               # 演示结束务必恢复
```

之后升级链会自动往前提（实测用的是 45 秒档）：

```
16:01:56  起：P13_Activity_0az5u78（草稿）+ P13_Event_1o398fk（Wait 7 days）   ← 两个并行令牌
16:02:16  P13_Gateway_1vkb7lp -> P13_Activity_1xgw427（审阅临床内容）
16:02:42  P13_Event_1o398fk 触发 -> P13_Gateway_0r6f1k1（letterComplete = false）-> 记录逾期信函
16:02:44  每周提醒顾问（自动）-> P13_Event_13dkl6t（Wait 23 days）
16:03:30  P13_Event_13dkl6t 触发 -> 升级给行政经理（自动）
16:04:15  P13_Event_0vf9q3w（Wait 2 months）触发 -> 升级给高级管理层 -> 高级复核
16:04:17  P13_Event_1ljjocr（升级分支结束）
```

讲解时值得点出的三点：

- 草稿任务的输出映射会写 `letterComplete = false`；如果**没**在第一个定时器触发前点掉它，网关读到的是缺失变量，
  会走默认边「停发提醒」—— 所以先点草稿（45 秒很从容，15 秒不够）；
- 升级分支结束时信函分支还开着，实例会停在 `P13_Activity_1xgw427`（`ACTIVE`）；想让实例走完，就把信函也点掉；
- `python timer_demo.py status` 看已部署版本；新实例总是用最新版本，所以**务必用 `restore` 收尾**。

### 12.3 阶段 8 的复评定时器 —— 不用改模型

`Timer_Reassessment`（"Wait until clinical reassessment date"）的时长来自**变量**：`= reassessmentDelay`（表单字段，默认 `P7D`）。
在表单里填一个短的 ISO-8601 时长，定时器就会真触发：

```bash
cd _analysis
HOSPITAL_CLINICAL_DECISION=delay HOSPITAL_REASSESSMENT_DELAY=PT20S python walkthrough_driver.py --wait 115
```

```powershell
# PowerShell
$env:HOSPITAL_CLINICAL_DECISION="delay"; $env:HOSPITAL_REASSESSMENT_DELAY="PT20S"; python walkthrough_driver.py --wait 115
```

实测：`R8_ClinicalReview COMPLETED 16:06:51 -> Gateway_ClinicalDecision 16:06:52 -> Timer_Reassessment COMPLETED 16:07:13（20.3 秒）
-> 16:07:13 直接回到流程 8`，紧接着又跑了一轮 —— 也就是「延期治疗 → 等复评日期 → 再复核」这个循环，当场可见。

未验证（本机无法验证）：Tasklist 在不同身份/权限下的可见性（c8run 无鉴权）、靠改时钟来加速定时器（remote 模式做不到 —— 12.2 用缩短时长的副本代替）。

# Job Worker 学习指南（医院患者路径 `1-17.bpmn`）

> **本文件与当前模型同步**：可执行流程是 **`Process_Hospital_Integrated`**，共 **51 个自动步骤 / 50 个
> job type / 14 个 worker 类**；模型只有一个普通起始事件 `Start_Tasklist`，其余入口都是
> 事件网关 `Gateway_ExternalTriggerRouter`（"Wait for referral or other external message"）后面的
> catch event——其中 12 个是新的 `Inbox_*` 元素。权威索引是
> [`WORKER_MAP.md`](WORKER_MAP.md)（job type ↔ 模型元素 ↔ worker ↔ 读写的表：前五列由模型与源码派生、
> 由 `BpmnModelCoverageTest` 守着不许漂移，Records 两列是人工维护、由 `WorkerPersistenceTest` 抽查）；
> 运行与部署步骤见
> [`README.zh-CN.md`](README.zh-CN.md)。

**这份文件是学习与导航用的说明文档**：每个 worker 属于哪个阶段、读什么变量、写什么变量、把哪些业务事实
写进嵌入式 H2 系统之记录、发什么消息、谁来接收、失败与重试怎么走，以及怎么亲手验证它真的在工作。

> 派生规则：改 worker 或模型时，先更新 `WORKER_MAP.md`，再同步本文件，最后跑 §11 的测试。

---

## 1. 30 秒总览

| 事实 | 数字 |
| --- | --- |
| `@JobWorker` 方法（job type 数） | **50** |
| 需要 worker 的自动化步骤 | **51** = 37 个 `sendTask` + 14 个 `serviceTask` |
| 为什么 51 个步骤只有 50 个 job type | `send-referral-outcome` 被 `R2_Decline`（拒绝）和 `R2_SendAcceptedOutcome`（接受）两个元素共用 |
| 承载 worker 的类 | **14** 个（`io.camunda.demo.hospital.worker` 包） |
| 会发布 BPMN 消息的方法 | **15** 个（共 15 个消息名；患者回复是三条互斥消息之一） |
| 只写变量、不发消息的方法 | 35 个 |
| 嵌入式 H2 的表 | **17** 张（`src/main/resources/db/schema.sql`） |
| 不需要集群的测试 | `BpmnModelCoverageTest`(7) + `HospitalDatabaseTest`(6) + `WorkerPersistenceTest`(6) + `CorridorMessageAuditTest`(2) |
| 需要本地 c8run 集群的测试 | `HospitalPathwayProcessTest` |

一句话心智模型：**模型提出问题，worker 给出答案，并把这个答案变成记录**——返回值直接成为流程变量，
分支网关据此分流；同时 worker 通过 `HospitalDatabase` 把业务事实写进 H2，后面的步骤再读回来。

---

## 2. 模型与 worker 之间的契约

1. **谁需要 worker。** 只有 `serviceTask`（14 个）和 `sendTask`（37 个）会生成 job；`userTask`（70 个）
   属于 Tasklist，不由本项目实现；`intermediateThrowEvent` 在这个模型里是 link 事件（39 对），不带 job。
2. **job type 就是合同。** 模型里 `zeebe:taskDefinition type="..."` 的字符串必须与
   `@JobWorker(type = "...")` **完全一致**（大小写、连字符），否则该 job 永远没人领取、实例卡死。
3. **返回值 = 流程变量。** 自动化步骤基本**没有** `zeebe:ioMapping`（唯一例外是
   `P13_Activity_19r74id`，额外输出 `letterComplete = true`），worker 返回的 `Map` 直接写进流程变量；
   "task-local ↔ process 变量"的映射只存在于表单/用户任务上。
4. **worker 不会"失败"。** 没有 BPMN error：每个 worker 总是成功返回一个答案或一条记录；业务上的"不行"
   （没号源、没授权、支付失败）都表达为**变量值**，由网关卡条件分流。
5. **两级重试要分清。**
   * **job 级重试**：`retries`（模型里部分步骤显式写 `retries="3"`，其余走 Camunda 默认 3）。
   * **业务级重试**：模型里的定时器 + 变量里的**重试计数器**（`consultationRetryCount`、
     `treatmentServiceRetryCount`、`paymentRetryCount`、`reminderCount`），见 §7。
6. **哪一步写哪张表。** worker 只通过门面 `HospitalDatabase` 访问系统之记录，`worker` 包里**没有**任何
   JDBC 类型（有测试守着）；`WORKER_MAP.md` 的 "Tables of the pathway" 一节就是"表 ↔ 读/写它的 job type"
   的映射（人工维护，不逐格断言）：`WorkerPersistenceTest` 跑演示路径与三条"同一转诊再来一次"的用例，
   逐步断言写了哪些行、哪些值是从记录读回来的。

---

## 3. 阶段 → 类 → worker

合并模型只有一个可执行池 `Process_Hospital_Integrated`；下面按元素 id 前缀分组（阶段 4、9 没有 worker 是正常的：
这两段是人的判断，落在用户任务和网关上）。

| 阶段（BPMN 元素前缀） | worker 类 | 个数 | 这一段的记录（写 / 读） |
| --- | --- | ---: | --- |
| 1 Receive Referral（`R1_`） | `ReferralWorkers` | 1 | `patient`、`referral` |
| 2 Review Referral（`R2_`） | `ReferralWorkers` | 1 | `patient`、`referral` |
| 3 Arrange Initial Consultation（`R3_`） | `ConsultationWorkers` | 5 | `treatment_service`、`slot_request`、`appointment` |
| 4 Diagnosis and Consent（`R4_`） | —（纯用户任务 + 消息） | 0 | 阶段 5 的第一个 worker 补记 `consultation` |
| 5 Arrange Treatment（`R5_`） | `TreatmentWorkers` | 3 | `consultation`、`treatment_cycle`（`treatment_service` 由目录解析写入） |
| 6 Verify Funding（`R6_`） | `FundingWorkers` | 1 | `funding_authorisation` |
| 6 Verify Cost Allocation（`P6_`） | `FundingClearanceWorkers` | 3 | `payment`、`funding_clearance`、`funding_authorisation`、`enquiry` |
| 7 Process Payment（`R7_`、`P7_`） | `PaymentWorkers` | 4 | `payment` |
| 8 Continue Treatment（`P8_`） | `ContinuationWorkers` | 5 | `enquiry`、`treatment_cycle`（读） |
| 9 Treatment Changes（`P9_`） | —（纯用户任务） | 0 | — |
| 10 Appointment Outcomes（`P10_`） | `PatientContactWorkers` | 1 | `referral`（读） |
| 11 Refunds and Fund Transfers（`P11_`） | `RefundWorkers` | 3 | `refund_case` |
| 12 Patient Enquiries（`P12_`） | `EnquiryWorkers` | 2 | `enquiry` |
| 13 Clinic Letters（`P13_`） | `ClinicLetterWorkers` | 7 | `clinic_letter`、`patient`、`consultation`（读） |
| 14 Follow-up Appointments（`P14_`） | `PatientContactWorkers` | 3 | `appointment`、`clinic_letter`（读） |
| 15 Monitoring and Reports（`P15_`） | `ReportingWorkers` | 4 | `report`、`pathway_record`、`event_log` |
| 16 Identity and Interruptions（`P16_`） | `IdentityAccessWorkers` | 2 | `access_audit`、`event_log` |
| 17 Appointment Changes（`P17_`） | `AppointmentChangeWorkers` | 5 | `appointment` |
| | | **50** | |

模型里另有 8 个**不可执行**的池（`Process_Referrer`、`Process_Patient`、`Process_Scheduling`、
`Process_TreatmentServices`、`Process_Funder`、`Process_PSP`、`Process_Correspondence`、
`Process_DelayTracking`）。它们只声明消息的收发方向，引擎不执行它们——这就是为什么有些 worker
"发出消息后只记录一句日志就结束"。

---

## 4. 逐个 worker 详解

读法：**读** = worker 从 job 变量里取的**关键**变量（括号内是源码里的兜底默认值）；**写** = 返回值里的
关键变量；**记录** = 通过 `HospitalDatabase` 写/读的表；**消息** = 交给 `ExternalPartyMessenger` 发布的
BPMN 消息。完整逐元素清单见 [`WORKER_MAP.md`](WORKER_MAP.md)。

### 阶段 1–2：`ReferralWorkers`（`send-referral-outcome` 一处实现、两处使用）

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `send-referral-documents-request`（`R1_RequestDocs`） | `requestMissingDocuments` | `referralId`、`patientId`、`patient_name`、`patientContact`、`referralReceivedDate`、`missingDocuments`(`referral letter`) | `receivedDocumentReferences`=`DOC-<ref>-01; DOC-<ref>-02`、`referralUpdateNotes` | 写 `patient`、`referral`；读 `patient`、`referral` | `Requested referral documents` |
| `send-referral-outcome`（`R2_Decline`、`R2_SendAcceptedOutcome`） | `sendReviewOutcome` | `referralSuitable`(false)、`referralPriority`、`referringClinician`、`referrerName`、`externalReference`、`referralDecisionReason` | `referralOutcome`=`accepted`/`declined`、`referralDecisionReason` | 写 `patient`、`referral`（状态 `accepted`/`declined`）；读 `patient`、`referral` | `Receive referral outcome message` |

* **要点**：这两步是**整条路径的登记入口**：转诊包里带的姓名和联系方式写到 `patient`（患者本体由
  R1_RegisterCheck 这一步人工在 PAS 登记，模型不携带出生日期，worker 不会凭空补），转诊本身写成
  `referral`——后面所有聚合都指向它。已在库里的转诊走 `update`，不会插出第二行。
* **要点**：`referralSuitable` 是用户任务 `R2_Review` 写出的结果，worker 只是把它翻译成一句对外结论；
  同一个方法同时服务"拒绝"和"接受"两条路径——**这正是 51 个步骤只有 50 个 job type 的原因**。

### 阶段 3：`ConsultationWorkers`（第 1 条业务级重试回路）

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `request-consultation-slot`（`R3_RequestSchedule`） | `requestConsultationSlot` | `referralId`、`selectedService`、`consultationRetryCount`(0)、`clinicianId`、开关 `slot-availability`、`retry-attempts-before-success` | `availability`、`date_of_appointment`(今天+14)、`appointmentTime`(`10:30`)、`clinicianId`、`clinic_notes` | 写 `treatment_service`（首次登记所选的科室/服务）、`slot_request`；读 `treatment_service`、`slot_request` | `Receive appointment options message` |
| `send-appointment-confirmation`（`R3_BookSendOffer`） | `bookSlotAndSendOffer` | `date_of_appointment`、`appointmentTime`、开关 `patient-decision` | `bookingReference`=`BKG-<ref>`、`appointmentDate`、`appointmentTime`、`appointmentOfferSent=true` | 写 `appointment`、`slot_request`（置 `booked` 并回填预约号）；读 `appointment`、`slot_request`、`treatment_service` | `Patient appointment response` / `Alternative consultation date` / `Patient declines consultation`（三选一） |
| `record-pending-consultation`（`R3_Waitlist`） | `keepConsultationPending` | `consultationRetryCount` | `consultationRetryCount`(+1)、`waitlistEntryReference`=`WL-<ref>-<n>`、`patientNotified=true`、`waitlistNotifiedTeam` | 写 `slot_request`（保持 `open`、`availability=unavailable`）；读 `slot_request` | —（定时器 `R3_RetryWait` 回到请求步骤） |
| `forward-appointment-change`（`R3_Task_ForwardAlternative`） | `forwardAppointmentChange` | `alternativeAppointmentDate` | `appointmentChangeForwarded`、`appointmentChangeReference`=`CHG-<ref>`、`appointmentChangeType`=`date` | — | —（直接 link 进 process 17） |
| `forward-scheduling-conflict`（`R3_Task_ForwardConflict`） | `forwardSchedulingConflict` | — | `schedulingConflictEscalated`、`schedulingConflictReference`=`CONF-<ref>`、`escalationReason` | — | —（直接 link 进 process 17） |

* **要点**：`selectedService` 是**自由文本**（表单 02 的 "Selected clinic / service"），而 `appointment`、
  `slot_request`、`treatment_cycle` 都按 code 指向 `treatment_service`。`ServiceCatalogue` 负责翻译：
  按 code、再按 name 查找；医院不认识的科室就在这一步登记（code 由名称派生，周期数取 `cycleNumber`，
  提前期取"今天到报出的预约日"的天数）。这样挂号、预约、疗程永远指向**同一行**目录数据。
* **要点**：重试计数器的"家"在 `slot_request.attempt_count`：等待分支读回这一行再保持 `open`，所以实例
  重启后询问次数不会丢。

### 阶段 5：`TreatmentWorkers`（第 2 条业务级重试回路）

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `request-treatment-service`（`R5_RequestService`） | `requestTreatmentService` | `treatmentServiceRetryCount`(0)、`selectedService`、`assessmentDate`、`treatmentPlanReference`、`diagnosis`、`informedConsent`、`consentDate`、`consentRecordedBy`、开关 `treatment-services-available`、`retry-attempts-before-success` | `allServicesAvailable`、`serviceReservationReferences`=`LAB-<ref>; IMG-<ref>`、`externalServiceNotes` | 写 `consultation`（含同意结论）；读 `appointment`、`consultation` | `Treatment or external service availability` |
| `record-pending-treatment-service`（`R5_KeepPending`） | `keepTreatmentServicePending` | `treatmentServiceRetryCount` | `treatmentServiceRetryCount`(+1)、`treatmentWaitingListReference`=`TWL-<ref>-<n>`、`treatmentServiceTeamNotified=true` | — | —（定时器 `R5_RetryService` 回到请求步骤） |
| `publish-provisional-treatment-booking`（`R5_TreatmentBooked`） | `publishProvisionalTreatmentBooking` | `selectedService`、`cycleNumber`(1)、`provisionalTreatmentDate`、`finalTreatmentDate`、`treatmentDate` | `provisionalBookingPublished`、`provisionalBookingReference`=`PRV-<ref>`、`treatmentBookingsNotified` | 写 `treatment_cycle`；读 `treatment_service`（提前期）、`treatment_cycle` | — |

* **要点**：阶段 4 没有 worker（评估与知情同意是用户任务），所以**同意后的第一个自动步骤**——这里要外部
  服务容量的一步——把 `consultation` 连同同意结论写下来；信件流程之后就能凭它说明"这封信讲的是哪次会诊"。
* **要点**：`treatment_cycle` 一次疗程一行（`referral + service + cycle_number` 唯一），到期复评时
  `request-next-treatment-cycle` 读回最大周期号，下一程就是它加一。

### 阶段 6：`FundingWorkers`

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `request-funding-authorisation`（`R6_RequestAuth`） | `requestFundingAuthorisation` | `payerName`/`payerType`、`estimatedCost`(`1250.00`)、`approvedFundingAmount`、`currency`(`GBP`)、`funderDecisionReason`、开关 `funding-authorised` | `fundingAuthorised`、`fundingReference`=`FUND-<ref>`、`approvedFundingAmount`、`fundingLimits`、`currency`、`funderDecisionReason` | 写 `funding_authorisation`；读 `funding_authorisation`、`treatment_cycle`（挂到当前疗程） | `Funding authorisation decision` |

* **要点**：申请与决定是**同一行**（worker 在同一次激活里既问又记），被拒绝的申请也留着理由；未授权时
  `authorised_amount` 为 NULL 而不是 0，表示"没有批钱"。

### 阶段 6（P6）：`FundingClearanceWorkers`

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `send-funding-clearance`（`P6_Task_SendFundingClearance`） | `sendFundingClearance` | `approvedFundingAmount`、`chargeAmount`、`clearanceReference`、`fundingFollowUpDate`、`currency` | `paymentRequired=false`、`fundingClearanceSent`、`fundingClearanceReference`、`fundingClearanceAmount`、`fundingClearanceDate` | 写 `funding_clearance`（必要时补写 `funding_authorisation`）；读 `funding_authorisation`、`funding_clearance`、`treatment_cycle` | — |
| `request-advance-payment`（`P6_Task_RequestPayment`） | `requestAdvancePayment` | `chargeAmount`（回退 `approvedFundingAmount`） | `paymentRequired=true`、`advancePaymentRequested`、`advancePaymentReference`=`ADV-<ref>`、`advancePaymentAmount`、`advancePaymentRequestedDate` | 写 `payment`（开出**本转诊唯一的一笔 charge**，状态 `requested`）；读 `payment`（是否已有扣款） | — |
| `reply-to-finance-enquiry`（`P6_Task_SendFinanceReply`） | `replyToFinanceEnquiry` | `financeEnquirySummary`、`financeEnquiryResponse` | `financeResponseSent`、`financeResponseReference`=`FIN-RES-<ref>`、`financeResponseDate`、`financeResponseSummary` | 写 `enquiry`（关闭并回答）；读 `enquiry` | — |

* **要点**：放款必须指向一条出资决定。R6 走过外部授权时那条决定就在库里；如果 `R6_AuthNeeded` 回答"不需要
  外部授权"，这一步就把路径自己记录的额度（payer/金额/理由）写成这次转诊的出资决定，再对它放款。
* **要点**：一个转诊**只有一笔 charge**（键 `PAY-<referralId>`），一条生命周期：这一步把它开成
  `requested`；阶段 7 的 `request-payment` 在**同一行**补上支付方的答复（`paid`/`failed`，重试也改这一行）；
  退款与资金转移步骤反的就是这笔"已收款"（`paid`）的 charge。

### 阶段 7：`PaymentWorkers`（第 3 条业务级重试回路 + 防重复扣款）

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `request-payment`（`R7_SendPaymentRequest`） | `requestPayment` | `chargeAmount`(`1250.00`)、`currency`、`invoiceReference`、`paymentRetryCount`、开关 `payment-outcome` | `paymentRetryCount`(+1)、`paymentStatus`=`paid`/`failed`、`paymentReference`=`PAY-<ref>`、`paidAmount`、`paymentDate`、`paymentFailureReason` | 写 `payment`（补全 charge）；读 `payment`（是否已有已收款的 charge） | `Payment result from provider` |
| `check-payment-idempotency`（`R7_CheckPriorCharge`） | `checkPaymentIdempotency` | `paymentRetryCount`、开关 `prior-charge-found`、`retry-attempts-before-success` | `priorChargeFound`、`priorChargeReference`、`paymentAttempts` | 读 `payment`（`findPaidByReferralId`） | —（网关 `R7_NoChargeFound` 直接读 `priorChargeFound`） |
| `flag-payment-investigation`（`R7_FlagInvestigation`） | `flagPaymentForInvestigation` | — | `investigationReference`=`INV-<ref>`、`financeNotified=true`、`rechargeBlocked=true` | — | — |
| `send-payment-confirmation-to-bookings`（`P7_Task_SendPaymentConfirmation`） | `sendPaymentConfirmationToBookings` | `paymentReference` | `paymentConfirmationSent`、`paymentConfirmationReference`=`PCF-<ref>`、`confirmedPaymentReference`、`confirmedClearedAmount`、`treatmentBookingsNotified` | 读 `payment`（已收款的 charge）、`funding_clearance`（可动用的放款） | — |

* **要点（本流程最值得理解的一段）**：`checkPaymentIdempotency` 的
  `priorChargeFound = 配置值 || (paymentRetryCount > retryAttemptsBeforeSuccess) || 库里已有"已收款"的 charge`。
  前两项保证**重试回路一定有终点**（超限即转 `flag-payment-investigation`，`rechargeBlocked=true`），
  第三项才是真正的"查系统之记录"：已经收过钱的 charge 绝不会被再收一次。
* **要点**：步骤本身**不会**因为库里查不到而少给答案——它只在"已收款"这个事实上让步：失败重试的分支里
  没有已收款的 charge，所以仍然可以再试一次。

### 阶段 8：`ContinuationWorkers`

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `reply-to-clinical-enquiry`（`P8_Task_SendClinicalReply`） | `replyToClinicalEnquiry` | `clinicalEnquiryResponse`、`clinicalResponseSummary` | `clinicalResponseSent`、`clinicalResponseReference`=`CLIN-RES-<ref>`、`clinicalResponseDate`、`clinicalResponseSummary` | 写 `enquiry`（回答并关闭）；读 `enquiry` | — |
| `request-next-treatment-cycle`（`P8_Task_SendNextCycle`） | `requestNextTreatmentCycle` | `selectedService` | `nextCycleRequested`、`clinicalDecision=continue`、`nextCycleRequestReference`=`CYC-<ref>`、`nextCycleRequestDate`、`nextCycleNumber`、`nextCycleServiceCode`、`serviceDefaultCycles` | 写 `treatment_cycle`（把复评结论 `fitToContinue`/`clinicalDecision` 写回被复评的那一程）；读 `treatment_cycle`（最大周期号 + 1）、`treatment_service`（`default_cycles`：该服务通常要几程） | — |
| `send-treatment-plan-change`（`P8_Task_SendPlanChange`） | `sendTreatmentPlanChange` | — | `treatmentPlanChangeSent`、`action=change`、`planChangeReference`=`PLN-<ref>`、`planChangeDate` | — | — |
| `return-clinical-priority-decision`（`P8_Task_SendPriorityDecision`） | `returnClinicalPriorityDecision` | — | `priorityDecisionReturned`、`priorityDecisionReference`=`PRI-<ref>`、`priorityDecisionDate`、`priorityDecision` | — | — |
| `refer-treatment-stop-to-finance`（`P8_Task_SendRefundCase`） | `referTreatmentStopToFinance` | — | `refundCaseReferred`、`clinicalDecision=stop`、`action=stop`、`refundCaseReference`=`RFD-CASE-<ref>`、`refundCaseReferredDate` | — | — |

### 阶段 10 / 14：`PatientContactWorkers`

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `inform-referring-organisation`（`P10_Task_NotifyReferrer`） | `informReferringOrganisation` | `caseEventType`(`cancellation`) | `referrerNotified=true`、`referrerNotification`（按 `no-show`/`refusal`/其他分支给不同文案） | 读 `referral`（通报对象的正式名称） | — |
| `request-follow-up-slot`（`P14_Task_RequestSlot`） | `requestFollowUpSlot` | 开关 `treatment-services-available` | `slotAvailable`、`date_of_appointment`(今天+21)、`appointmentTime`(`09:15`)、`availability`、`followUpLetterReference`、`followUpSlotNote` | 读 `clinic_letter`（复诊由哪封信触发） | `Receive appointment options message` |
| `book-follow-up`（`P14_Task_Book`） | `bookFollowUpAppointment` | `requestedReviewBy`(今天+28)、`selectedService`、`requestingClinician` | `followUpBookingReference`=`FUB-<ref>`、`followUpAppointmentDate`、`followUpSlotAvailable=true`、`schedulingServiceNote` | 写 `appointment`（复诊预约 `APPT-FU-<ref>`）、`treatment_service`（首次登记）；读 `appointment`、`treatment_service` | — |
| `notify-patient`（`P14_Task_Notify`） | `notifyPatientAboutAppointment` | — | `patientNotified=true`、`patientNotificationMethod`、`patientNotificationDate` | — | — |

* **要点**：消息流跨越池边界时**引擎不执行它**，所以 P10 的 worker 只记录"信息已离开医院"——但它写进
  文案的机构名来自 `referral` 记录，而不是流程变量里的自由文本。

### 阶段 11：`RefundWorkers`

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `send-refund-request`（`P11_Task_SendRequest`） | `sendRefundRequest` | `approvedRefundAmount`/`refundAmount`/`paymentAmount`(`1250.00`)、`refundType`、`transferBookingReference`、开关 `refund-outcome` | `refundStatus`=`completed`/`rejected`、`refundReference`=`RFD-<ref>`、`refundedAmount`、`refundProviderNote` | 写 `refund_case`；读 `payment`（要退的那笔已收款 charge）、`refund_case` | `Receive PSP refund result message` |
| `record-refund-outcome`（`P11_Task_RecordResult`） | `recordRefundOutcome` | `refundStatus`(`completed`)、`refundedAmount`(`0.00`) | `refundRecorded`、`refundRecordedDate`、`refundRecordedAmount`、`patientAccountUpdated` | 读 `refund_case`（金额与结案日期从记录读） | — |
| `link-fund-transfer-records`（`P11_Task_LinkRecords`） | `linkFundTransferRecords` | `transferBookingReference`(`BKG-<ref>`) | `linkedBookingReference`、`fundTransferLinked=true`、`transferLinkedDate` | 写 `refund_case`（回填 `linked_booking_reference`）；读 `refund_case` | — |

* **要点**：案件类型由模型决定——带 `transferBookingReference`（转给别的预约）是 `fund-transfer`，其余是
  `refund`；案件必须指向一笔**已收款**的 charge，路径上没有任何 charge 时（例如患者在同意阶段就拒绝、
  医院还没收过钱）就没有"要退的钱"，此时不建案件、只发请求。

### 阶段 12：`EnquiryWorkers`

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `refer-clinical-enquiry`（`P12_Activity_1cxqg1o`） | `referClinicalEnquiry` | `enquirySummary`、`contactChannel`、`raisedBy`、`patientContact`、`clinicalContactTeam`(`Duty clinical team`) | `clinicalEnquiryReference`=`CLIN-ENQ-<ref>`、`clinicalEnquiryReferredDate`、`clinicalEnquiryTeam` | 写 `enquiry`（状态 `open`）；读 `enquiry` | — |
| `refer-finance-enquiry`（`P12_Activity_0iv7m7o`） | `referFinanceEnquiry` | `financeEnquirySummary`、`enquirySummary`、`contactChannel` | `financeEnquiryReference`=`FIN-ENQ-<ref>`、`financeEnquiryDate`、`financeEnquiryTeam`(`Finance team`) | 写 `enquiry`（状态 `open`）；读 `enquiry` | — |

* **要点**：两个都是**院内**移交，不发消息——问题先落成 `enquiry` 行，之后由别的阶段（P6 答财务、P8 答临床）
  读回来回答并关闭，所以"谁答的、什么时候答的"可查。

### 阶段 13：`ClinicLetterWorkers`（7 个 worker，全部围绕患者信件）

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `open-clinic-letter`（`P13_Service_OpenLetter`） | `openClinicLetter` | `patientId`、`referralId`、`patient_name`、`letterStartDate`、`appointmentDate`、`letterRecipients`、`clinicalReviewFindings`、`diagnosis` | `clinicLetterId`=`LET-<pid>`、`clinicLetterRecordOpened`、`clinicLetterOpenedDate` | 写 `clinic_letter`（草稿）；读 `patient`（姓名按记录拼）、`consultation`、`clinic_letter` | — |
| `send-letter-to-secretaries`（`P13_Activity_0wdxagy`） | `sendLetterToSecretaries` | `patientId` | `letterEntryReference`=`LET-<pid>`、`letterEntryReceivedDate` | 写 `clinic_letter`（`approved`）；读 `clinic_letter` | `LetterFromDoctor` |
| `send-approved-letter-to-correspondence`（`P13_Task_SendToCorrespondence`） | `sendApprovedLetterToCorrespondence` | `patientId` | `correspondenceReference`=`CORR-<pid>`、`letterEntryReference`=`ENTRY-<pid>`、`letterEntryReceivedDate` | 写 `clinic_letter`（`sent`）；读 `clinic_letter` | `Receive letter entry for release message` |
| `distribute-approved-letter`（`P13_Activity_19r74id`） | `distributeApprovedLetter` | `patientId`、`letterRecipients`(`patient, referring clinician`)、`distributionMethod` | `distributionReference`=`DIST-<pid>`、`distributionDate`、`distributionMethod`、`letterComplete=true` | 写 `clinic_letter`（`complete` + `sentOn`）；读 `clinic_letter` | — |
| `return-letter-to-consultant`（`P13_Activity_1t7mm3x`） | `returnLetterToConsultant` | `patientId` | `correctionRequestReference`=`COR-<pid>`、`correctionRequestedDate`、`letterStatus`=`correction requested` | 写 `clinic_letter`（`correction requested`）；读 `clinic_letter` | `LetterBackForRechecking` |
| `send-consultant-reminders`（`P13_Activity_0aygpgf`） | `sendConsultantReminders` | `patientId`、`reminderCount` | `reminderCount`(+1)、`reminderSentDate`、`consultantResponseReference`=`RES-<pid>`、`consultantResponse` | — | `MessageFromDoctor` |
| `escalate-letter-to-manager`（`P13_Activity_0ccs70w`） | `escalateLetterToManager` | `patientId` | `escalationReference`=`ESC-<pid>`、`escalationDate`、`escalationReport` | — | `ReportFromDoctor` |

* **要点**：这组 worker 的相关键是 **`patientId`**（不是 `referralId`），因为信件流程按患者维度关联；
  `letterVariables(job)` 会为 `patientId` / `referralId` / `patient_name` 补默认值（`PAT-1001` /
  `REF-1001` / `Alex Morgan`），手工启动信件流程做测试时 worker 仍然可用。
* **要点**：信件状态是记录的一部分：`draft → approved → sent → complete`（或 `correction requested`），
  每一步都读回同一行再改；`ClinicLetterRepository.findDrafts` 因此总是"还没处理的信"。

### 阶段 15：`ReportingWorkers`（报告关于系统之记录本身）

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `collect-pathway-data`（`P15_Task_Collect`） | `collectPathwayData` | 不读案例变量 | `reportDataSetReference`=`DS-<jobKey>`、`dataSetCollectedAt`、`dataSetSources`、`dataSetRecordCount`（真实计数） | 读 `referral`、`appointment`、`clinic_letter`、`enquiry`、`funding_authorisation`、`payment`、`refund_case`、`pathway_record`、`event_log` | — |
| `generate-pathway-reports`（`P15_Task_Generate`） | `generatePathwayReports` | `reportingPeriod`、`requestedBy`、`reportDataSetReference` | `reportReferences`(6 张报表)、`reportGeneratedAt`、`reportDataSetReference`、`reportCount`(6) | 写 `report`（一次运行一张报表一行，行数=该聚合的真实计数）；读 6 个聚合 + `report` | — |
| `publish-pathway-reports`（`P15_Task_Publish`） | `publishPathwayReports` | `reportReferences`(`RPT-ALL`) | `publishedReportReferences`、`publishedAt`、`reportAccessScope`（角色化访问）、`reportsPublished` | 读 `report`（发布的就是记录里那份） | — |
| `record-pathway-update`（`P15_Task_UpdateMonitoringRecord`） | `recordPathwayUpdate` | `referralId`、`patientId`、`changedRecord`、`changeSummary`、`currentStage`、`followUpDue` | `monitoringRecordUpdated`、`monitoringUpdateReference`=`PR-<ref>`、`monitoringUpdateRecordedAt`、`auditTrailUpdated`、`updatedDataSetReference` | 写 `pathway_record`、`event_log`；读 `pathway_record` | — |

* **要点**：这三个"采集/生成/发布"步骤是**唯一完全不读案例变量**的 worker（连 `referralId` 都不读），
  引用号只用 `job.getKey()` 派生；但它们**读系统之记录**：数据集大小是查出来的计数，报告行数是各聚合的
  真实行数，发布时列出的是记录里已有的报表——所以监控路径既能作为案例路径的一环被调用，也能被
  `Monitoring run or report request` 独立启动。

### 阶段 16：`IdentityAccessWorkers`（审计与对账）

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `record-access-decision`（`P16_Task_Audit`） | `recordAccessDecision` | `targetStaffUserId`(`unknown-user`)、`requestType`、`approvedRole`/`requestedRole`、`accessAuthorised`、`permissionRationale`/`verificationNotes`、`authorisingAdministrator` | `auditRecordReference`=`AUD-<jobKey>`、`auditRecordedAt`、`auditRecordedBy`、`auditEntry` | 写 `access_audit`（只追加）、`event_log` | — |
| `reconcile-offline-work`（`P16_Task_Reconcile`） | `reconcileOfflineWork` | `incidentReference`(`INC-<jobKey>`)、`referralId` | `reconciliationReference`=`REC-<incident>`、`reconciledOfflineRecords`、`reconciledAuditRecords`、`reconciledAt`、`auditHistoryPreserved=true` | 读 `access_audit`、`event_log`（把留存的历史数出来） | — |

* **要点**：`access_audit` 是**只追加**表（只有 `append`，没有 `update`）：权限被收回是**新的一行**，不是
  改动旧行——审计行如果能被改写，就不能证明"谁在什么时候决定了什么"。

### 阶段 17：`AppointmentChangeWorkers`

| job type（模型元素） | 方法 | 读 | 写 | 记录（写/读） | 消息 |
| --- | --- | --- | --- | --- | --- |
| `request-appointment-change-options`（`P17_Task_RequestSlot`） | `requestAppointmentChangeOptions` | 开关 `treatment-services-available` | `slotAvailable`、`date_of_appointment`(今天+10)、`appointmentTime`(`11:00`)、`availability`、`appointmentChangeOptionsNote` | 写 `appointment`（置 `change-requested`）；读 `appointment` | `Receive appointment options message` |
| `notify-changed-appointment`（`P17_Task_NotifyPatient`） | `notifyChangedAppointment` | `date_of_appointment`、`appointmentTime` | `appointmentChanged`、`patientNotified`、`changedAppointmentDate`（读自预约记录）、`changeNotificationReference`=`CHG-NOT-<ref>` | 写 `appointment`（改期 + `booked`）；读 `appointment` | — |
| `refer-funding-change`（`P17_Task_ReferFundingChange`） | `referFundingChange` | — | `fundingChangeReferred`、`fundingChangeReference`=`FND-CHG-<ref>`、`fundingChangeReferredDate`、`fundingChangeTeam` | — | — |
| `refer-treatment-plan-change`（`P17_Task_ReferPlanChange`） | `referTreatmentPlanChange` | — | `planChangeReferred`、`planChangeReference`=`PLN-CHG-<ref>`、`planChangeReferredDate`、`planChangeAuthoriser` | — | — |
| `request-clinical-priority-review`（`P17_Task_SendPriorityReview`） | `requestClinicalPriorityReview` | — | `priorityReviewRequested`、`priorityReviewReference`=`PRI-REV-<ref>`、`priorityReviewDate` | — | — |

* **要点**：改约请求是**按转诊**关联的，所以它也可能早上门——那条转诊在系统之记录里还没有预约记录。
  这时两步只提出并公布新的可选时间，不会改动任何行；有记录时才标记 `change-requested`、再改期。

---

## 5. 谁决定分支：变量 → 生产者 → 网关

| 决策变量 | 主要生产者 | 读取它的网关 | 说明 |
| --- | --- | --- | --- |
| `referralSuitable` | 用户任务 `R2_Review` | `R2_Suitable` | 被 `send-referral-outcome` 读取 |
| `slotAvailable` | 用户任务 `R3_RecordAvailability`（源：worker 返回的 `availability`） | `R3_SlotAvailable` | worker 给建议、人做确认 |
| `allServicesAvailable` | worker `request-treatment-service` | `R5_AllAvailable` | worker 直接决定 |
| `fundingAuthorised` | worker `request-funding-authorisation`（可被用户任务覆盖） | `R6_AuthApproved` | 外部授权 + 人工复核 |
| `paymentStatus` | worker `request-payment` | `R7_PaymentPaid` | 失败后进入人工闸门 |
| `retryApproved` | 用户任务 `R7_NotifyRetry` | `R7_RetryAllowed` | **必须由人批准才允许重试** |
| `priorChargeFound` | worker `check-payment-idempotency` | `R7_NoChargeFound` | 库里的"已收款"事实 + 硬终止条件 |
| `providerConfirmsPaid` | 用户任务 `R7_ReconcilePayment` | `R7_ReconciledPaid` | 人工与支付方核对 |
| `informedConsent` | 患者同意消息（阶段 4）/ 用户任务 `Patient_DecideConsent` | `R4_Consent` | 阶段 4 无 worker，由阶段 5 的 `request-treatment-service` 记入 `consultation` |
| `fitToContinue` | 用户任务 `P8_Task_Evaluate` | `P8_Gateway_Fit` | 阶段 8 无 worker |

> 读法：**"worker 提议、人拍板"** 的变量（`slotAvailable`、`fundingAuthorised`、`paymentStatus`、
> `retryApproved`）体现了模型刻意保留的责任边界；**"worker 直接决定"** 的变量
> （`allServicesAvailable`、`priorChargeFound`）才是纯自动化。

---

## 6. 消息总表

`ExternalPartyMessenger` 是 worker 与外部世界的唯一出口；相关键取自 `HospitalMessages`，必须与模型的
`zeebe:subscription correlationKey` 一致（有测试守着）。**worker 发布 15 个消息名**（其中 14 个有等待者：
`Receive referral outcome message` 发给不可执行的参照方池）：

| 发布者（job type） | 消息名 | 相关键 | 合并池里等待它的元素 |
| --- | --- | --- | --- |
| `send-referral-documents-request` | `Requested referral documents` | `referralId` | `R1_ReceiveDocs` |
| `send-referral-outcome` | `Receive referral outcome message` | `referralId` | **无**（发给不可执行的参照方池 `Process_Referrer`） |
| `request-consultation-slot` / `request-follow-up-slot` / `request-appointment-change-options` | `Receive appointment options message` | `referralId` | `R3_ReceiveSchedule`、`P14_Catch_SchedulingResponse`、`P17_Catch_SchedulingResponse` |
| `send-appointment-confirmation` | `Patient appointment response` | `referralId` | `R3_PatientResponse` |
| `send-appointment-confirmation` | `Alternative consultation date` | `referralId` | `R3_AlternativeDate` |
| `send-appointment-confirmation` | `Patient declines consultation` | `referralId` | `R3_PatientDeclined` |
| `request-treatment-service` | `Treatment or external service availability` | `referralId` | `R5_ReceiveService` |
| `request-funding-authorisation` | `Funding authorisation decision` | `referralId` | `R6_ReceiveAuth` |
| `request-payment` | `Payment result from provider` | `referralId` | `R7_ReceivePaymentResult` |
| `send-refund-request` | `Receive PSP refund result message` | `referralId` | `P11_Catch_RefundResult` |
| `send-approved-letter-to-correspondence` | `Receive letter entry for release message` | `referralId` | `P13_Catch_CorrespondenceEntry` |
| `send-letter-to-secretaries` | `LetterFromDoctor` | `patientId` | `P13_Activity_0mfrh8d`（receiveTask） |
| `return-letter-to-consultant` | `LetterBackForRechecking` | `patientId` | `P13_Activity_0f4hol4`（receiveTask） |
| `send-consultant-reminders` | `MessageFromDoctor` | `patientId` | `P13_Activity_0m2occw`（receiveTask） |
| `escalate-letter-to-manager` | `ReportFromDoctor` | `patientId` | `P13_Activity_0ae9rsk`（receiveTask） |

下面 19 个消息名**不由 worker 发布**（合并池共等 33 个消息名、38 个等待点 = 34 个 intermediate
catch event + process 13 的 4 个 receiveTask）：13 条入口事件由 `ExternalEventService` 发布，其中 4 条
`Patient appointment preference` / `Patient consents message` / `TestResultsMessage` /
`System or external service restored` 由 `PatientAutoResponder` 按间隔重复发布，另有 2 个名字**没有任何发布者**。
`Next treatment cycle due`、`Patient attends a treatment cycle message`、`TreatmentChangeRequestMessage`、
`AppointmentCancellationReportMessage`、`PatientQuestionMessage`、`Monitoring run or report request`、
`A pathway record changes message`、`Staff identity or access change request`、
`System or external-service interruption`、`Patient requests a change to an existing appointment message`、
`Patient appointment preference`、`Patient consents message`、`Patient refuses treatment message`、
`TestResultsMessage`、`System or external service restored`、`Clinic letter completed`。

后两个名字（`Patient refuses treatment message`、`Clinic letter completed`）**可等待但永远没有发布者**，
而且都是无害的：`R4_Catch_Refusal` 是事件网关 `R4_Gateway_WaitPatientDecision` 的另一条分支，患者的拒绝
是作为**同意消息**带着 `informedConsent = false` 到达的，由 catch 之后的排他网关 `R4_Consent` 走
“No”（默认）分支进拒绝路径（“Yes” 分支的条件是 `informedConsent = true`），所以不需要拒绝消息；
`Clinic letter completed` 由 process 13 的三个完成定时器
（`P13_Catch_CompletedSevenDay` / `P13_Catch_CompletedOneMonth` / `P13_Catch_CompletedThreeMonth`）等待，
本项目没有步骤发布它——演示里这三个定时器就是等超时，信件的完成状态由 `clinic_letter.status` 和
`letterComplete` 变量表达。

### 入口 catch event：12 个 `Inbox_*` 元素

除 `Start_Tasklist`（唯一的普通起始事件）和 `R1_Start`（转诊包的入口）之外，所有外部入口都是
事件网关 `Gateway_ExternalTriggerRouter`（"Wait for referral or other external message"）后面的
`Inbox_*` catch event；它们各自把消息交给本段的第一个业务步骤：

| 入口元素 | 等待的消息 | 交给哪一段 |
| --- | --- | --- |
| `Inbox_R4_Start` | `Clinic visit completed` | 阶段 4（门诊就诊完成 → 诊断与知情同意） |
| `Inbox_P6_Start_FinanceInquiry` | `Finance enquiry received message` | 阶段 6 的费用咨询（`P6_Task_RespondFinanceInquiry`） |
| `Inbox_P8_Start_CycleDue` | `Next treatment cycle due` | 阶段 8 的复评入口 |
| `Inbox_P8_Start_PatientArrives` | `Patient attends a treatment cycle message` | 阶段 8 的患者到场 |
| `Inbox_P9_Start_ChangeRequest` | `TreatmentChangeRequestMessage` | 阶段 9 的治疗变更 |
| `Inbox_P10_Start_Report` | `AppointmentCancellationReportMessage` | 阶段 10 的取消/拒绝/爽约 |
| `Inbox_P12_StartEvent_1` | `PatientQuestionMessage` | 阶段 12 的患者咨询 |
| `Inbox_P15_Start_Monitor` | `Monitoring run or report request` | 阶段 15 的监控运行（`P15_Task_Collect`） |
| `Inbox_P15_Start_RecordUpdate` | `A pathway record changes message` | 阶段 15 的记录更新（`P15_Task_UpdateMonitoringRecord`） |
| `Inbox_P16_Start_Access` | `Staff identity or access change request` | 阶段 16 的权限申请 |
| `Inbox_P16_Start_Interruption` | `System or external-service interruption` | 阶段 16 的系统中断 |
| `Inbox_P17_Start_ChangeRequest` | `Patient requests a change to an existing appointment message` | 阶段 17 的改约请求 |

**发布机制的两条规则**（都在 `ExternalPartyMessenger` 里）：
1. **答案带 TTL（15 分钟）。** 医院总是**先发请求、后到接收任务**，所以引擎会缓冲答案，等订阅一建立
   立刻关联；没人等的答案（没走到的分支）静默过期。
2. **本模型没有任何"启动消息"。** 唯一的普通起始事件是 `Start_Tasklist`，其余 38 个消息等待点
   （34 个 intermediate catch event + process 13 的 4 个 receiveTask）都在实例内部等消息；订阅从部署
   那刻起就存在，所以按 `ExternalPartyMessenger.isStartMessage` 的判定，全部消息都走带 TTL 的分支。
3. 发布失败**只打 WARN，不让 job 失败**——模拟环境里"没人等"是正常状态。

---

## 7. 三条业务级重试回路 + 一个提醒计数器

### 7.1 咨询号源（阶段 3）

```text
R3_RequestSchedule ──> R3_ReceiveSchedule ──> (人工确认 R3_RecordAvailability) ──> R3_SlotAvailable
                                                                                      │ false
                                                                                      v
                                                                            R3_Waitlist（计数器 +1）
                                                                                      │
                                                                        R3_RetryWait（schedulingRetryDelay）
                                                                                      │
                                                                                      └──> 回到 R3_RequestSchedule
```

* 计数器：`consultationRetryCount`（读：`request-consultation-slot`；加一：`record-pending-consultation`），
  同一份计数也写在 `slot_request.attempt_count` 上，等待分支读回记录。
* 成功条件：`consultationRetryCount >= retry-attempts-before-success`（默认 1）**且** `slot-availability=available`。
* **等待时长**：`R3_RetryWait` 的 duration 是变量 `schedulingRetryDelay`（由 `R3_RecordAvailability` 提供），
  **为空时默认 `P1D`**——演示时不设这个变量就要等一天。

### 7.2 外部服务容量（阶段 5）

```text
R5_RequestService ──> R5_ReceiveService ──> R5_AllAvailable
                                               │ false
                                               v
                                     R5_KeepPending（计数器 +1）
                                               │
                                   R5_RetryService（treatmentServiceRetryDelay）
                                               │
                                               └──> 回到 R5_RequestService
```

* 计数器：`treatmentServiceRetryCount`（读：`request-treatment-service`；加一：`record-pending-treatment-service`）。
* 成功条件：`treatmentServiceRetryCount >= retry-attempts-before-success` **且** `treatment-services-available=true`。

### 7.3 支付（阶段 7）——带人工闸门与终止条件

```text
R7_SendPaymentRequest（paymentRetryCount +1）──> R7_ReceivePaymentResult ──> R7_PaymentPaid
                                                                                │ failed
                                                                                v
                                                        (人工) R7_NotifyRetry ──> R7_RetryAllowed
                                                                                 │ true        │ false
                                                                                 v             v
                                                               R7_CheckPriorCharge     R7_FlagInvestigation
                                                                       │
                                                              R7_NoChargeFound
                                                          false ───────┴─────── true
                                                            │                    │
                                              回到 R7_SendPaymentRequest   R7_FlagInvestigation
```

* 计数器：`paymentRetryCount`（`request-payment` 每次加一；`check-payment-idempotency` 读取）。
* 终止条件：`paymentRetryCount > retry-attempts-before-success` → `priorChargeFound = true` → 转入调查分支，
  `rechargeBlocked = true`；另外，库里只要已有"已收款"的 charge，也立即视为有交易——**循环一定有终点，
  也绝不会重复扣款。**

### 7.4 信件催办计数

`reminderCount` 只由 `send-consultant-reminders` 维护。模型里的三个定时器（`P13_Event_1o398fk`、
`P13_Event_13dkl6t`、`P13_Event_0vf9q3w`）时长分别是 `P7D`、`P23D`、`P2M`，**累计**起来对应网关
"Still outstanding after 7 days / at 1 month / at 3 months" 的三个检查点，从而形成
7 天提醒 → 1 个月 → 3 个月升级的行政催办节奏（不影响上面的三条回路）。

---

## 8. 配置开关 → 影响哪个 worker

开关只改 worker 的返回值或发布内容，**从不改模型**（见 `application.properties` 与 `SimulationProperties`）。

| 开关（`hospital.simulation.*`） | 默认 | 影响的 worker |
| --- | --- | --- |
| `slot-availability` | `available` | `request-consultation-slot` |
| `retry-attempts-before-success` | `1` | `request-consultation-slot`、`request-treatment-service`、`check-payment-idempotency` |
| `patient-decision` | `accept` | `send-appointment-confirmation`（选发哪条消息、预约状态 `booked`/`offered`） |
| `treatment-services-available` | `true` | `request-treatment-service`、`request-follow-up-slot`、`request-appointment-change-options` |
| `funding-authorised` | `true` | `request-funding-authorisation` |
| `payment-outcome` | `paid` | `request-payment` |
| `prior-charge-found` | `false` | `check-payment-idempotency` |
| `refund-outcome` | `completed` | `send-refund-request` |
| `patient-consent-decision` | `consent` | `request-treatment-service`（同意结论的兜底） |
| `default-patient-id` / `default-referral-id` / `default-patient-name` | `PAT-1001` / `REF-1001` / `Alex Morgan` | 需要写 `patient` / `referral` 的 worker（变量缺省兜底）；`ClinicLetterWorkers` 也用它补相关键 |
| `test-results-fit-to-continue`、`default-incident-reference`、`auto-responder-enabled`、`auto-responder-interval` | 见文件 | **不是 job worker**，作用于 `PatientAutoResponder`（见 §9） |

---

## 9. 除 worker 之外的角色

| 组件 | 作用 | 为什么它不能是 job worker |
| --- | --- | --- |
| `HospitalDatabase`（persistence） | 系统之记录的唯一门面：17 个聚合各有一个访问器，`worker` 包里没有任何 JDBC 类型 | 它是被 worker 注入的基础设施，不是流程步骤 |
| `ExternalPartyMessenger`（support） | worker 发布消息的通道；相关键解析、15 分钟 TTL、`withoutCorrelationKey()`、失败只 WARN | 它是被 worker 调用的工具 |
| `Variables`（support） | `copyOf/text/flag/number/nextAttempt/date/moment/amount`；`flag()` 同时认布尔值和表单的 `"yes"` | 纯工具 |
| `ServiceCatalogue`（support） | 把模型里的自由文本 `selectedService` 解析成目录 code（先按 code、再按 name），医院不认识的服务在首次使用时登记 | 它是读写的辅助，不是流程步骤 |
| `PathwayAudit`（support） | 审计轨迹：把完成的 job 和外部来的走廊消息写进 `event_log` | 同上 |
| `PatientAutoResponder`（simulation） | 每 10 秒发布 `Patient appointment preference`、`Patient consents message`、`TestResultsMessage`、`System or external service restored` | 这四条消息来自**患者本人/实验室/外部系统**，医院侧没有对应步骤 |
| `ExternalEventService`（simulation） | 13 条入口事件（推荐信、门诊就诊完成、费用咨询、周期到期、患者到场、变更请求、取消报告、患者提问、监控请求、记录变更、权限申请、中断报告、改约请求）+ 5 条"不请自来"的答复；每条都写进 `event_log` | 它们**启动**路径或模拟外部世界，天然没有前驱 job |
| `ScenarioRunner`（simulation） | 启动时按 `hospital.simulation.scenario` 发一条外部事件，方便一次演示 | 应用启动钩子 |

> 也就是说：**"外部世界主动说" → `ExternalEventService`/`PatientAutoResponder`；
> "医院问了外部才答" → 请求步骤的 job worker 自己回答。**

---

## 10. H2 系统之记录

* **表**：`src/main/resources/db/schema.sql` 建 17 张表（patient、referral、treatment_service、
  appointment、slot_request、consultation、treatment_cycle、funding_authorisation、funding_clearance、
  payment、refund_case、clinic_letter、enquiry、access_audit、pathway_record、report、event_log），
  全部 `CREATE TABLE IF NOT EXISTS`；`db/data.sql` 用 `MERGE ... KEY` 播种 5 个服务目录、演示患者
  `PAT-1001` 与转诊 `REF-1001`。应用每次启动重放这两个脚本：服务目录是参考数据，每次启动按 key
  重新写入（`MERGE`）；演示患者与转诊是**可变**的（worker 会写联系方式、状态、优先级），所以只在 key
  不存在时插入一次——**重启保留一次演示已经写下的数据，不会回到初始值**；要从种子状态重来就删掉
  `java/data/`（运行时 H2 文件所在目录）。
* **文件库**：运行时是 `java/data/hospital-pathway.mv.db`（已在 `java/.gitignore` 里）；测试用
  `src/test/resources/application.properties` 指向内存库 `jdbc:h2:mem:hospital-pathway`，**测试永远不会
  碰开发者的文件库**。
* **门面**：worker 只注入 `HospitalDatabase`（`referrals()`、`payments()`、… 17 个访问器），
  SQL 与 JDBC 全部留在 `io.camunda.demo.hospital.persistence` 里；`worker` 包出现 JDBC 类型会被
  `WorkerPersistenceTest` 判失败。
* **谁写谁读**：完整映射见 [`WORKER_MAP.md`](WORKER_MAP.md) 的 "Tables of the pathway" 一节——
  17 张表每张都至少有一个 worker 写、一个 worker 读（这张映射人工维护，不逐格断言）。
  `WorkerPersistenceTest` 按演示路径逐个跑 worker，并在每一步之后断言记录里多出了什么、读回的值是否
  真的来自记录（例如 `priorChargeFound`、`nextCycleNumber`、`refundRecordedAmount`、；三条“同一转诊再来一次”的用例覆盖 `slot_request` / `refund_case` / `enquiry` 的重复写入。
  `dataSetRecordCount`、`followUpLetterReference`）。
* **为什么用 JDBC 而不是 JPA**：语句保持可见、确定，演示时能直接看到 worker 写了哪一行；持久层本身由
  `HospitalDatabaseTest` 守着（建表、每个聚合一次往返、外键、播种幂等）。

---

## 11. 怎么观察和验证

1. **启动**：启动 c8run 集群 → 一次性部署 `1-17.bpmn` + 全部 `.form` → `mvn spring-boot:run`。
   命令与注意事项见 [`README.zh-CN.md`](README.zh-CN.md)。**模型没部署时 worker 什么都做不了。**
2. **看日志**：每个 worker 都打一行 `INFO`，形如
   `referral REF-1001: scheduling service answered 'available' on attempt 2 (job 2251799813685385)`。
   按 logger 名过滤即可只看到某一组 worker。
3. **看 Operate / Tasklist**：在实例里找 job，能看到 job type、`retries` 剩余次数、incident；在 Tasklist
   里完成用户任务后，回到 Operate 会看到 worker 自动完成了下一个 job。
4. **看数据库**：跑完演示后查 `java/data/hospital-pathway.mv.db`（例如 `SELECT * FROM payment;`），
   就能看到 worker 在每一步写下的行。
5. **跑测试**（**Maven 的工作目录必须是 `java/`**，测试用 `Path.of("..")` 从 JVM 工作目录找模型）：
   * `.\mvnw.cmd -B "-Dtest=BpmnModelCoverageTest" test`（7 个，不需要集群）——断言 §2 的契约：
     每个 service/send task 都有可用 job type、每个 job type 都有 worker、每个 worker 都有对应模型元素、
     每条被等待的消息都能发布、相关键与模型一致、被引用的表单都存在且 id 唯一。
   * `.\mvnw.cmd -B "-Dtest=HospitalDatabaseTest" test`（4 个，不需要集群）——建表、每个聚合一次
     往返、外键、播种幂等。
   * `.\mvnw.cmd -B "-Dtest=WorkerPersistenceTest" test`（2 个，不需要集群）——按演示路径驱动 worker，
     断言每一步写/读了哪张表，并断言 `worker` 包里没有 JDBC 类型。
   * `.\mvnw.cmd -B "-Dtest=!HospitalPathwayProcessTest" test`——上面三个一起跑（无需集群的全部）。
   * `.\mvnw.cmd -B test`——只有本地 c8run 集群在 `127.0.0.1:26500` 时才跑得通；
     `HospitalPathwayProcessTest` 会真的部署模型、驱动整条路径并验证 worker 完成真实 job。
6. **快速对表**：当前契约只看两处——`WORKER_MAP.md`（模型 51 步 / 50 个 job type）与
   `BpmnModelCoverageTest`（它直接读 `../1-17.bpmn`）。`_analysis/` 下的
   `model-job-types.txt` / `worker-job-types.txt` 是**上一版模型**的快照（各 30 行），不要当当前契约用。

---

## 12. 一页速记

* **50 个 worker = 51 个自动化步骤**（37 sendTask + 14 serviceTask），因为 `send-referral-outcome` 一个实现
  服务 `R2_Decline` 与 `R2_SendAcceptedOutcome` 两个元素。
* worker 的**返回值就是流程变量**，网关拿它分流；`consultationRetryCount`、`treatmentServiceRetryCount`、
  `paymentRetryCount`、`reminderCount` 是四个跨 job 的记忆。
* **15 个方法会发消息**（15 个消息名，其中 14 个有等待者），全部经 `ExternalPartyMessenger`；答案带
  15 分钟 TTL，本模型没有启动消息。另外 19 个消息名不由 worker 发布：13 条入口事件（`ExternalEventService`，
  其中 4 条 `PatientAutoResponder` 也按间隔重复）+ 2 个**没有任何发布者**的消息名
  （`Patient refuses treatment message`、`Clinic letter completed`，都由模型自身兜住，见 §6）。
* **三条业务级重试回路**（咨询、外部服务、支付）+ 一个提醒计数器；支付回路有硬终止条件
  （`priorChargeFound` 由超限或"库里已有已收款 charge"强制为真），保证不会重复扣款。
* **每一次业务事实都落到 H2**：17 张表每张都有 worker 写、worker 读，映射写在 `WORKER_MAP.md`，
  并被 `WorkerPersistenceTest` 当断言跑；worker 只认 `HospitalDatabase` 门面。
* **一次转诊一笔 charge**：`request-advance-payment` 开单（`requested`）、`request-payment` 在同一行
  写支付方答复（`paid`/`failed`）、退款/资金转移反的就是这笔已收款的 charge。
* 改任何东西之后：更新 `WORKER_MAP.md` → 同步本文件 → 在 `java/` 下跑 §11 的测试。

# Version 2 验收测试计划

本计划复用 version1 的 `deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx`（AT-01—AT-16），原文副本保存在 [docs/source](source/Hospital_Patient_Pathway_Acceptance_Test_Plan.version1.docx)。业务预期保留，流程 ID、节点、变量和资源数量按 version2 映射。临床紧急程度、适宜性和授权决定由测试数据明确给出，不生成新的临床判定规则。

## 测试对象和边界

- version1：`HospitalPatientPathway/1-17.bpmn`，`Process_Hospital_Integrated`。
- version2：`HospitalPatientPathway-redesign/hospital-patient-pathway.bpmn`，`Process_HPAS`。
- version2 资源基线：1 个可执行医院流程、37 个独立表单、55 种 Worker 类型、7 个外部模拟参与者、9 个非中断事件子流程。原计划的 16 个共享表单/81 个协作用户任务/50 种 Worker 数量不沿用。
- `Version2AcceptanceIT` 在真实 Camunda 8.9 上检查流程路由。外部服务 Worker 使用 CPT 替身，不据此声称真实支付、通知或全部业务表写入成功。
- `OriginalPlanParityIT` 保留原计划的决策契约及真实 Worker 拒绝规则；缺失业务能力会产生失败，不会以替换变量或删除原标准来变成通过。
- 已移植的 H2/JDBC 测试独立验证数据库 schema、仓储、初始化与审计；数据库测试通过不替代流程验收。

## 固定输入

当前有其他任务同时保存主图。本轮测试使用 `evidence/acceptance-input/` 下的固定快照，来源及 SHA-256 见 `manifest.json`。表单存为 `.form.keep`，BPMN 存为 `.bpmn.keep`，部署时恢复资源名。

本轮发现主文件含空 `BPMNDiagram`，引擎拒绝部署。快照显式清除了这些空 DI 图层；不修改执行节点、流、条件、消息或表单。该操作逐项记在 manifest，不能把快照部署成功等同于并行修改中的主文件已通过部署。

重新冻结主图和表单：

```powershell
python tools\freeze_acceptance_input.py
# 仅在需要诊断空图层且明确接受测试快照规范化时：
python tools\freeze_acceptance_input.py --normalize-empty-di
```

## 验收场景与映射

| AT | 保留的原业务预期 | Version 2 输入/节点与用例 | 尚需满足的原标准 |
| --- | --- | --- | --- |
| AT-01 | 同批部署模型与表单，无重复或缺失资源 | `at01_bundleDeploys` + 6 项静态模型契约；部署快照全部 38 个资源 | 原主文件的 Schema 与 lint 结果单独登记；引擎部署和 lint 是两个检查 |
| AT-02 | 完整、适宜、非紧急转诊进入诊疗并最终结案 | `documentsComplete=true`、`reviewDecision=accept`；`at02_completeSuitableReferral` 从 Top_Start 走到 Top_End_Closed | 完整路径须无 incident；外部应答为受控模拟 |
| AT-03 | 缺资料只向正确实例关联应答并重新审核 | `missingDocumentsAreRequestedAndCorrelatedBack`；两条实例、同名消息、不同 referralId | 不允许另一条实例消费应答 |
| AT-04 | 拒绝不适宜转诊，通知并结束，不进入预约治疗 | `declinedReferralEndsAfterSendingTheOutcome`；`reviewDecision=reject`，另测 information→redirect | 保留禁止进入预约/治疗的断言 |
| AT-05 | 有号源推进，无号源/紧急/超期进入明确人工处理，电话联系有升级 | slotFound、urgentReferral、withinTwoWeeks、phoneAttempts；含 3 次联系失败升级 | 原 requestedReviewBy 日期/重试窗口标准未因 slotFound 布尔值而视为已验证 |
| AT-06 | 无知情同意不得预约或实施治疗 | `consentGiven=false`；`at06_consentRefusal` | 禁止激活内部/外部预约、收费和治疗实施 |
| AT-07 | 服务不可用或资金被拒时不启动治疗 | externalAvailable=false 等待释放；hospital/exempt/insurer/patient-pay 分支及无效申请纠正 | 原 fundingAuthorised=false 阻断标准保留为契约检查 |
| AT-08 | 支付成功只记录一次，已有扣款不得重新请求 | paymentStatus=completed/confirmation-lost/failed；调查 taken/not-taken，人工 retry/urgent-override | priorChargeFound/已扣款检查须实现并验证；确定性请求 ID 不自动等同于重复收费已被阻止 |
| AT-09 | 可继续者继续，停止优先于变更 | fitnessDecision=fit/delay/change；周期消息、延期和变更等待 | 原 clinicalDecision=stop 优先级目前没有等价 stop 路由，保留失败检查 |
| AT-10 | 未批准不得变更；涉及费用时实施前重新核查 | urgentChange、formallyAuthorised、financialImpact；紧急追认与常规分支、财务/退款 | 当前财务检查在 ApplyChange 之后；原实施前重查标准保留 |
| AT-11 | 未验证身份不可改约；日期不超期；14 天内电话联系 | changeKind=reschedule/other、slotAcceptable=true/false | 身份、requestedReviewBy 日期边界和近期开约电话规则分别登记，不能以 slotAcceptable 代替三个标准 |
| AT-12 | 取消改约、临床复核、未到诊分别处理 | attendanceEvent=cancelled/declined/no-show；pathwayResponse=re-offer/clinical-review/close；已付费触发财务 | 各路线的结束、禁止遗留 incident 单独断言 |
| AT-13 | 已授权退款，与资金转移分流 | refundDue=true/false + financeApprover；真实 Worker 授权拒绝测试 | 原 refundAuthorised=false 否决、transfer 独立路线仍保留，不把“无退款”当作“资金转移” |
| AT-14 | 财务问询分配、批准信件发布、临床疑错退回、7 天/周期提醒 | enquiryClass、infoAuthorised、urgentConcern；letterApproved、adminCheck=clinical-error/ok；原 P7D 计时器 | 使用受控时钟快进原计时器；时钟不可变时登记 BLOCKED，不改成短时计时器并声称原标准通过 |
| AT-15 | 权限允许/拒绝；按 incidentReference 恢复后才对账 | accessAuthorised 布尔值、eventKind=access/interruption；真实 Worker denied 输出 | 原恢复消息关联标准仍需实现；“记录停机后立即对账”仅是当前路线样例 |
| AT-16 | 所有人工作业表单可用，Worker 与消息契约完整 | 部署 37 个表单，路由测试按 `.form` 字段形状提交，静态检查 55 种 Worker | API 提交不替代浏览器 form-js 渲染；历史 37/37 截图单独作为旧证据，当前浏览器审计仍需重跑 |

## 运行方法

普通回归（数据库、Worker 审计、当前主文件静态契约）：

```powershell
cd java
.\mvnw.cmd test
```

完整验收（含未实现的原计划断言，可能按真实缺口返回失败）：

```powershell
cd java
.\mvnw.cmd -Pacceptance test
cd ..
python tools\report_acceptance_results.py
```

流程测试通过 remote CPT 连接测试专用的 Camunda 8.9，默认 REST 8080、gRPC 26500、监控 9600。CPT 会清理其运行状态，必须使用可重置的开发/测试集群。可用 `-Dacceptance.rest=...`、`-Dacceptance.grpc=...` 覆盖客户端地址。要运行原计时器快进测试，测试引擎启动时设置 `ZEEBE_CLOCK_CONTROLLED=true`；不在生产集群启用。

## 证据和判定

- 逐测试结果：`java/target/surefire-reports/`。
- BPMN 路由覆盖：`java/target/coverage-report/report.html`，以 Process_HPAS 数据计算，绝不以 JUnit 通过率冒充分支覆盖率。
- 本轮输入：`evidence/acceptance-input/manifest.json`。
- 测试实例及清理前状态：`evidence/acceptance-instance-traces.jsonl`。CPT 可重置实例编号，须使用 runId + case + instanceKey 一起识别记录。
- 汇总：`evidence/acceptance-results.json` 和 `TEST_RESULTS_VERSION2.zh-CN.md`。
- PASS、FAIL、BLOCKED、仅静态验证和历史样例分开登记。任一原标准缺失时，该 AT 项最多标为部分完成，不声明整项通过。

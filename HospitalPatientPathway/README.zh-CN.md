# 医院患者路径：Camunda 表单与 Job Worker

[English](README.md) | **简体中文**

本文件夹包含 `1-17.bpmn` 综合模型以及 16 个 deployment-bound（部署绑定）的 Camunda 表单，覆盖流程阶段 1–17。`deliverables/` 现在仅保留用户提供的 [Acceptance Test Plan Word 文件](deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx)。

**模型已修复，现在可以真正部署并支持自动化** —— 详见下方[模型修复](#模型修复)。
**承载各自动化步骤的 Java job worker 位于 [`java/`](java/README.zh-CN.md)。**
完整的变更报告（改了什么、为什么改、如何验证）见 [`WORK_REPORT.md`](WORK_REPORT.md)。

## 在 Camunda Modeler 中使用

用 Camunda Modeler 打开 `1-17.bpmn`，并保持 `.form` 文件与模型在同一文件夹内。
模型使用部署绑定，因此**必须把 BPMN 与它引用的全部表单放在同一次部署中**。
每个共享表单通过 `hospitalFormTask` 只显示当前用户任务对应的那一段。
表单字段绑定到同名的任务局部变量，并通过显式的输入/输出映射与流程变量互相传递；
提交时只有当前活动任务的字段会被回传。任务与表单的完整对应关系见映射文件。

## 共享表单

| 表单文件 | 阶段 | 用户任务数 |
| --- | --- | ---: |
| 01_receive_referral.form | 1 转诊接收 | 4 |
| 02_review_referral.form | 2 转诊评估 | 2 |
| 03_initial_consultation.form | 3 初诊预约 | 4 |
| 04_diagnosis_consent.form | 4 诊断与知情同意 | 6 |
| 05_treatment_services.form | 5 治疗安排 | 5 |
| 06_cost_allocation.form | 6 费用与经费核对 | 5 |
| 07_payment_result.form | 7 支付处理 | 8 |
| 08_continue_treatment.form | 8 治疗延续 | 6 |
| 09_treatment_changes.form | 9 治疗方案变更 | 5 |
| 10_appointment_outcomes.form | 10 预约结果 | 4 |
| 11_refund_decisions.form | 11 退款与资金转移 | 2 |
| 12_patient_enquiries.form | 12 患者咨询 | 7 |
| 13_clinic_letters.form | 13 门诊信函 | 8 |
| 14_follow_up_appointments.form | 14 随访预约 | 3 |
| 16_identity_interruptions.form | 16 身份权限与系统中断 | 4 |
| 17_appointment_changes.form | 17 预约变更 | 8 |

协作模型中的 81 个用户任务全部配有部署绑定表单，其中 70 个属于可执行医院流程，11 个属于不可执行的外部参与者流程。流程 15 的报表采集、生成与发布任务是服务任务，
不使用用户表单。

这些表单采集流程所需信息，同时保持临床/行政/财务的职责边界；不索取完整卡信息。
紧急程度规则仍属于业务决策，问询表单只记录评估结论，不擅自引入临床紧急度策略。

`Form_Task_Mapping.csv` 是“任务 → 表单”索引，包含后续补充的预约变更和其他任务绑定。

## Job Worker

`java/` 是覆盖可执行医院流程自动化步骤的 Spring Boot worker 应用：**50 个 job type，覆盖 51 个自动化步骤**
（服务任务、发送任务以及 1 个消息抛出事件），并模拟所有外部参与方的回话
（转诊机构、患者、排班服务、检验/治疗服务、保险公司、支付服务商）。

已有运行证据使用本机 Camunda 8.9.19，端口为 26500 / 8080。请先启动兼容集群，再按下方命令部署。

```bash
# Git Bash，在本目录执行

# 1. 部署流程 —— 模型与表单必须在同一次部署里（表单是部署绑定的）
curl -X POST http://localhost:8080/v2/deployments \
  $(for f in 1-17.bpmn *.form; do printf ' -F resources=@%s' "$f"; done)

# 2. 启动 worker
cd java && ./mvnw spring-boot:run
```

```powershell
# PowerShell，在本目录执行

# 1. 部署流程（用 curl.exe，不要用 Invoke-WebRequest 的 curl 别名）
$form = Get-ChildItem *.form | ForEach-Object { '-F'; "resources=@$($_.Name)" }
curl.exe -sS -X POST http://localhost:8080/v2/deployments -F "resources=@1-17.bpmn" @form

# 2. 启动 worker
cd java; .\mvnw.cmd spring-boot:run
```

也可以用 Camunda Modeler 打开 `1-17.bpmn`（`.form` 放在同目录）直接点 Deploy，
替代上面的 curl 命令。[`MANUAL_TEST_GUIDE.zh-CN.md`](MANUAL_TEST_GUIDE.zh-CN.md) 是人工走查全流程：
哪一步在等哪条消息、Tasklist 里该填什么、启动消息必须带哪些变量（英文版 `MANUAL_TEST_GUIDE.md`）。

这个文件夹是 Camunda Modeler 的**流程应用**（根目录有 `.process-application` 标记），这也是为什么一次 Deploy
就能把模型和 16 个部署绑定表单一起发上去。注意：**这棵目录树里所有 `.bpmn` 和 `.form` 都会被一起发出去**，
混进一个副本就会让整次部署失败。

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `cvc-complex-type.3.2.2: attribute 'name' is not allowed ... bpmn:group` | 树里存在**未修复**的模型副本。本项目里就是 `_analysis/original-1-14named.bpmn`（你交付的原始模型，两个 group 带 `name` 属性） | 原件现在存为 `_analysis/original-1-14named.bpmn.keep`（非可部署后缀）；树里多于一个 `.bpmn` 时 `BpmnModelCoverageTest` 会失败 |
| `Duplicated process id in resources 'a.bpmn' and 'b.bpmn'` | 同一次部署里有两个都定义 `Process_Hospital_Integrated` 的模型，比如树里留了一份模型副本 | 整棵树里只保留一个 `.bpmn` —— 同一条守门断言 |
| 出现多余的表单版本 | 树里有表单的旧副本（如 `java/target/` 里的构建产物），会被当成同一 form id 的第二个版本一起部署 | 删掉构建产物（`mvn clean`）；同一个 form id 在树里出现两次时守门断言会失败 |

Problems 面板里还会列出关于 Modeler 自带连接器模板（AWS Bedrock、SQS、DynamoDB…）的警告，
那些不是模型错误，也不会阻止部署。

`java/README.zh-CN.md` 记录了 job type 与 worker 的对应关系、消息与关联键的设计、模拟开关以及验证结果；
`java/WORKER_MAP.md` 逐条列出每个模型元素与它的 worker。

## 模型修复（历史基线）

本节记录较早的修复阶段。最终模型、表单、worker 与实跑结果以提交索引及测试证据为准。

交付的模型**完全无法部署**，并且有 **12 个自动化步骤不可能被任何 worker 接单**。
所有改动都保持最小，逐一记录如下，并可用 `_analysis/repair_model.py` 从交付原件重放
（`_analysis/original-1-14named.bpmn.keep` 是未改动的原件，`_analysis/repair-log.txt` 是实际应用到的改动清单）。

另有第二个独立脚本 `_analysis/repair_start_events.py`（日志 `_analysis/repair-start-events-log.txt`）
把起始事件对齐案例：路径从**流程 1** 进入，流程 8 改为消息触发（见第 7 行）。

| # | 元素 | 问题 | 改动 |
| --- | --- | --- | --- |
| 1 | `P15_Group_Monitoring`、`P16_Group_AccessAndInterruptions` | `bpmn:group` 在 BPMN 2.0 中没有 `name` 属性，导致**整个模型 XSD 校验失败**（`cvc-complex-type.3.2.2`） | 删除 `name` 属性（该属性纯装饰） |
| 2 | `P13_Flow_ThreeMonth_Yes`、`P13_Flow_1p8oyk4` | 排他网关的分支既没有条件、也未标记为默认流；Zeebe 拒绝部署（"Must have a condition or be default flow"） | 两条"仍未完成"分支补上条件 `= letterComplete = false`，与同一流程的 7 天检查点保持一致 |
| 3 | `Message_P16_ServiceRestored` | 被 `P16_Catch_Restored` 捕获事件等待，但消息缺少 `zeebe:subscription`；Zeebe 拒绝部署（"Must have exactly one zeebe:subscription extension element"） | 补上 `correlationKey="= incidentReference"`，使"服务恢复"消息能回到上报中断的那条实例 |
| 4 | `P12_Event_09ty25j`、`P12_Event_0qw1zi1`、`P12_Event_11yxbm9` | 结束事件上挂着没有 `messageRef` 的 `messageEventDefinition`，以及 `type="end"/"normal"` 的 `zeebe:taskDefinition`（转写残留）；消息抛出事件必须有消息，普通结束事件也不是 job worker 元素 | 删除这两个残留，恢复为普通结束事件；三个结束事件仍是各问询路径的终点 |
| 5 | `P15_Task_Collect`、`P15_Task_Generate`、`P15_Task_Publish`、`P16_Task_Audit`、`P16_Task_Reconcile` | 服务任务完全没有 `zeebe:taskDefinition`，永远无法产生 job | 新增 job type：`collect-pathway-data`、`generate-pathway-reports`、`publish-pathway-reports`、`record-access-decision`、`reconcile-offline-work` |
| 6 | `P12_Activity_1cxqg1o`、`P12_Activity_0iv7m7o`、`P13_Activity_0wdxagy`、`P13_Activity_19r74id`、`P13_Activity_1t7mm3x`、`P13_Activity_0aygpgf`、`P13_Activity_0ccs70w` | 发送任务的 job type 是占位值（`send`、`pass information`）或节点 id 本身 | 改为规范 job type：`refer-clinical-enquiry`、`refer-finance-enquiry`、`send-letter-to-secretaries`、`distribute-approved-letter`、`return-letter-to-consultant`、`send-consultant-reminders`、`escalate-letter-to-manager` |
| 7 | `Start_Tasklist`、`P8_Start_CycleDue`、`Message_TasklistReferral` | 模型顶层**只有一个普通起始事件** `P8_Start_CycleDue`（流程 8），所以任何「Start instance」（Operate / Tasklist / Modeler）都直接进**流程 8**，而不是案例的流程 1，随后又因缺少 `= patientId` 关联键立刻出 incident | 把流程 1 的入口 `Start_Tasklist`（"Start referral in Tasklist"，对应案例 1.1 医务秘书受理）的**消息触发改为普通起始事件**，它成为模型唯一的默认入口；`P8_Start_CycleDue` 改为消息触发 `Next treatment cycle due`（对应案例 8.1 下一轮治疗到期），与流程 9–16 一致；不再被引用的 `Tasklist referral entry` 消息定义删除 |

在该历史修复阶段，模型其余部分未改动。当前提交包含后来完成的表单、网关、消息和布局调整。

## 仓库目录结构

下面的结构就是本文件夹被提交的真实结构。`java/target/`、`java/data/`、`java/.idea/` 与
`_analysis/` 已被 `.gitignore` 排除，不会出现在其中。

```text
HospitalPatientPathway/
├── .gitattributes                 二进制与文本产物的行尾规则
├── .gitignore                     构建产物、H2 文件与开发档案
├── .process-application           Camunda Modeler 标记：一次 Deploy 同时发送模型与表单
├── 1-17.bpmn                      可执行模型 Process_Hospital_Integrated
├── 01..14,16,17_*.form            16 个部署绑定的 Camunda 表单
├── Form_Task_Mapping.csv          任务到表单的索引，81 行（每个用户任务一行）
├── README.zh-CN.md / README.md    本文件及其英文版
├── WORK_REPORT.md                 变更报告：18 轮工作，改了什么、为什么
├── MANUAL_TEST_GUIDE.md           人工测试全流程（+ .zh-CN.md）
├── Process Review.html            导出的模型评审页
├── PROJECT_PLAN.md                产品待办、冲刺待办、计划证据、对照测试结果的修订记录
├── TEST_RESULTS.md                结果登记表：28/28 自动化测试，逐验收用例状态
├── BUSINESS_PROCESS_TEST_PLAN_EN.md  执行策略与证据索引
├── deliverables/
│   └── Hospital_Patient_Pathway_Acceptance_Test_Plan.docx
├── evidence/
│   ├── README.md                  三个证据目录的阅读方式
│   ├── tests/                     Surefire 摘要、XML 报告、Maven 日志
│   ├── validation/                Modeler/引擎校验、表单审计、FEEL 结果
│   └── runtime/                   部署响应 + 13 组实例（status/trace/jsonl）
├── java/
│   ├── pom.xml, mvnw, mvnw.cmd    带内置 wrapper 的 Maven 构建
│   ├── application-example.properties   worker 配置模板
│   ├── RUNTIME_COMPATIBILITY.md   集群与运行时版本说明
│   ├── README.md / README.zh-CN.md      worker 设计、job 类型、模拟开关
│   ├── WORKER_MAP.md              模型元素到 worker 的索引
│   ├── WORKER_GUIDE.zh-CN.md      worker 指南
│   └── src/main|test/java/io/camunda/demo/hospital/...
└── presentation/
    └── Hospital_Patient_Pathway_Presentation.pptx
```

## 文件清单

| 路径 | 内容 |
| --- | --- |
| `1-17.bpmn` | 合并后的路径模型（已修复，见上） |
| `*.form` | 16 个部署绑定的 Camunda 表单 |
| `Form_Task_Mapping.csv` | 任务到表单的索引，81 行 |
| `java/` | 覆盖各自动化步骤的 Spring Boot job worker |
| `PROJECT_PLAN.md` | 产品待办、冲刺待办、计划证据，以及对照测试结果修订计划的记录 |
| `TEST_RESULTS.md` | 结果登记表：28/28 自动化测试，逐验收用例的状态与证据 |
| `BUSINESS_PROCESS_TEST_PLAN_EN.md` | 执行策略、测试层级、证据索引、状态用语 |
| `WORK_REPORT.md` | 变更报告：模型修复、worker 设计、验证证据 |
| `MANUAL_TEST_GUIDE.zh-CN.md` | 人工测试全流程：入口、逐阶段操作、分支、消息清单、排查 |
| `MANUAL_TEST_GUIDE.md` | 人工测试全流程的英文版 |
| `Process Review.html` | 导出的模型评审页 |
| `presentation/` | 提交演示稿：背景与目标、架构、17 个流程、演示流程、测试结果 |
| `deliverables/` | 用户提供的 Acceptance Test Plan Word 文件 |
| `evidence/` | 筛选后的测试证据；本地 `_analysis/` 为开发档案，不进入标记版本 |
| `README.md` | 本 README 的英文版 |

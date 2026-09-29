# 工作文档：医院流程模型的修复与 Camunda 8 Job Worker 实现

> 本文记录早期修复阶段。最终提交的模型、16 个表单、50 个 job type、28/28 当前 Java 测试结果及仓库版本，以 [`submission/SUBMISSION_INDEX.md`](submission/SUBMISSION_INDEX.md) 和 [`submission/TEST_RESULTS.md`](submission/TEST_RESULTS.md) 为准。本文提到的 `_analysis/` 是本地开发档案，未全部纳入公开提交。

这份文档记录我在这个文件夹里**改了什么、为什么改、怎么验证的**。所有结论都来自实际执行的命令输出，
不是推测。文件位置：`Hospital_Patient_Pathway_Camunda_Forms_Bundle(1)/`。

| 产出 | 位置 |
| --- | --- |
| 本工作文档（中文） | `WORK_REPORT.md` |
| 修复后的流程模型 | `1-17.bpmn`（原模型备份在 `_analysis/original-1-14named.bpmn.keep`） |
| Job Worker 工程 | `java/`（24 个源文件，2418 行） |
| 模型修复的可重放脚本 | `_analysis/repair_model.py`（幂等，已复现验证） |
| 修复清单 | `_analysis/repair-log.txt` |
| 运行日志与测试证据 | `_analysis/full-test.log`、`_analysis/app-run5.log` |
| 英文说明（给工程用） | `README.md`、`java/README.md`、`java/WORKER_MAP.md` |

---

## 1. 一句话结论

原模型**一处都部署不上**（XSD 校验就挂了），而且有 **12 个自动化步骤没有任何可用的 job type**，
所以"只写 worker"是跑不通的。我做了两件事：

1. **修模型**：20 处最小改动，让模型能部署、每个自动化步骤都有 worker 能接单子；
2. **写 worker**：`java/` 下的 Spring Boot 应用，**30 个 `@JobWorker` 覆盖模型里全部 31 个自动化步骤**，
   并模拟了所有外部参与方（转诊方/患者/排班/检验/保险/支付）的回话。

验证：`mvn test` **10/10 通过**（含 5 个打真实集群的流程测试）；独立启动应用跑 demo 场景，
集群里查到实例 `COMPLETED`、三步骤元素全部 `COMPLETED`、无 incident。

---

## 2. 环境事实（这次踩到的坑，先记下来）

| 事实 | 影响 / 处理 |
| --- | --- |
| JDK 22（`C:\Program Files\Java\jdk-22`），`JAVA_HOME` 指向了不存在的 temurin-24 | `javap`、部分工具会报 `could not open ...jvm.cfg`；用绝对路径调用即可 |
| PATH 上的 `mvn`（Trae/VS Code 的 shim）**每次都会失败**：`找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher` | 它把 Maven home 解析成了 MSYS 路径。真正可用的是 `D:/maven/apache-maven-3.9.6`；仓库里放了包装脚本 `_analysis/mvnx.sh`，用原生 classpath 直接拉起 launcher |
| 本机**没有 Docker** | `camunda-process-test` 默认的 `managed` 运行时会去拉 `camunda/camunda:8.9.0` 镜像并失败；测试必须用 `runtime-mode=remote` 打本机集群 |
| 本机已有 c8run 8.9.0 集群在跑（gRPC 26500 / REST 8080 / actuator 9600） | 测试和 demo 都直接用它，不需要额外起环境。**运行中的是课程压缩包里的那份**：`camunda8-getting-started-bundle-8.9.0-windows-x86_64/c8run-8.9.0`（第 9 节记录了从旧副本 `c8/c8run-8.9.0` 切换过来的过程；两份端口相同，不能同时启动） |
| `.m2` 里已缓存 spring-boot 4.0.5、camunda 8.9.0、junit 6.0.3 等 | 可以 `mvn -o`（离线）构建，不依赖网络 |

---

## 3. 交付物清单

### 3.1 修改的文件

| 文件 | 改动 |
| --- | --- |
| `1-17.bpmn` | 20 处修复（详见第 4 节）；行尾保持 LF，forms/io mapping/网关/消息流/图形布局未动 |
| `README.md` | 新增「Job workers」和「Model repairs」两节 + 文件清单；原内容保留 |

### 3.2 新增的文件

| 路径 | 内容 |
| --- | --- |
| `WORK_REPORT.md` | 本文档 |
| `java/pom.xml` | Maven 工程（Spring Boot 4.0.5 + camunda-spring-boot-starter 8.9.0）；`maven-resources-plugin` 把上级目录的 `*.bpmn` `*.form` 复制进测试 classpath，保证测试用的就是部署用的那份模型 |
| `java/README.md` | 工程说明：怎么跑、job type 映射、消息与 correlation key 设计、模拟开关表、验证结果 |
| `java/WORKER_MAP.md` | 模型元素 → job type → worker 方法全表（脚本从模型和源码生成） |
| `java/src/main/resources/application.properties` | 客户端配置 + 全部模拟开关（默认走正常路径） |
| `java/src/main/java/.../HospitalPathwayApplication.java` | 启动类 |
| `java/src/main/java/.../config/SimulationProperties.java` | 模拟开关的类型化配置 |
| `java/src/main/java/.../support/ExternalPartyMessenger.java` | 发 BPMN 消息（外部参与方的回话），含两种 TTL 语义 |
| `java/src/main/java/.../support/HospitalMessages.java` | 模型里全部 29 条消息名称 + correlation key 变量 + 入口消息集合 |
| `java/src/main/java/.../support/Variables.java` | job 变量读写小工具 |
| `java/src/main/java/.../simulation/ExternalEventService.java` | 模拟"医院外部"的事件：转诊包、患者提问、取消、改方案、退款案、门诊完成、随访请求、监控请求、权限请求、中断报告、患者偏好/知情同意、检验结果、服务恢复 |
| `java/src/main/java/.../simulation/PatientAutoResponder.java` | 可选：定时发出患者/检验侧消息（默认关） |
| `java/src/main/java/.../simulation/ScenarioRunner.java` | 可选：启动即触发一个 demo 场景 |
| `java/src/main/java/.../worker/*.java`（10 个类） | 30 个 `@JobWorker` 方法，按阶段分组：转诊、初诊预约、治疗服务、经费、支付、退款、往来信函(P13)、问询(P12)、患者联系(P10/P14)、报表(P15)、身份与中断(P16) |
| `java/src/test/java/.../BpmnModelCoverageTest.java` | 不需要集群的一致性测试（5 个） |
| `java/src/test/java/.../HospitalPathwayProcessTest.java` | 打真实集群的流程测试（5 个） |
| `_analysis/repair_model.py` | 模型修复脚本（可重放、幂等） |
| `_analysis/mvnx.sh` | 本机可用的 Maven 包装脚本 |
| `_analysis/original-1-14named.bpmn.keep` | 你交付的原始模型（未改动的对照） |
| `_analysis/repair-log.txt` | 20 处改动清单 |
| `_analysis/model-job-types.txt` / `worker-job-types.txt` | 模型侧与代码侧的 job type 清单（diff 结果一致） |
| `_analysis/full-test.log` / `app-run5.log` | 测试与独立运行的完整日志 |

**没有改动的文件**：15 个 `.form`、`Form_Task_Mapping.csv`、`.process-application`，全部原样保留。

---

## 4. 模型改动（`1-17.bpmn`，共 20 处）

### 4.1 为什么必须改

第一次部署的报错原文：

```
cvc-complex-type.3.2.2: 元素 'bpmn:group' 中不允许出现属性 'name'。
```

修掉后又连续暴露出 Zeebe 的校验错误：

```
Element: Message_P16_ServiceRestored - ERROR: Must have exactly one zeebe:subscription extension element
Element: P13_Flow_ThreeMonth_Yes   - ERROR: Must have a condition or be default flow
Element: P13_Flow_1p8oyk4          - ERROR: Must have a condition or be default flow
```

同时静态检查发现：`P15_Task_Collect/Generate/Publish`、`P16_Task_Audit/Reconcile` 五个服务任务
**完全没有 `zeebe:taskDefinition`**，`P12` 两个、`P13` 五个 send task 的 job type 是
`send`、`pass information` 或节点 id 这类占位值——这些步骤在引擎里永远不会有 worker 收到单子。

### 4.2 改动明细

| # | 位置 | 问题 | 改动 | 依据 |
| --- | --- | --- | --- | --- |
| 1 | `P15_Group_Monitoring`、`P16_Group_AccessAndInterruptions` | `bpmn:group` 在 BPMN 2.0 里没有 `name` 属性 → **整个模型 XSD 校验失败** | 删除 `name` 属性 | 该属性纯装饰；Camunda Modeler 自身也不写它 |
| 2 | `P13_Flow_ThreeMonth_Yes`、`P13_Flow_1p8oyk4` | 网关的"仍有未完成"分支既无 condition 又非默认流 → Zeebe 拒绝部署 | 补条件 `= letterComplete = false` | 与同一流程 7 天检查点（`P13_Gateway_0r6f1k1`）用同一个变量、同一语义；两条"no"分支已被模型标为 default，逻辑不变 |
| 3 | `Message_P16_ServiceRestored` | 被 `P16_Catch_Restored` 等待，但消息没有 `zeebe:subscription` → Zeebe 拒绝部署 | 补 `correlationKey="= incidentReference"` | 中断处理路径由事件编号区分，用 `incidentReference` 做关联键语义正确 |
| 4 | `P12_Event_09ty25j`、`P12_Event_0qw1zi1`、`P12_Event_11yxbm9` | 结束事件上挂着**没有 `messageRef` 的** `messageEventDefinition`，外加 `taskDefinition type="end"/"normal"`（转写残留） | 删除这两个残留，恢复为普通结束事件 | 消息抛出事件必须有消息；普通结束事件不是 job worker 元素。校验器当时没报错，属潜在缺陷，先排掉 |
| 5 | `P15_Task_Collect/Generate/Publish`、`P16_Task_Audit/Reconcile` | 服务任务没有 job type → 永远无 worker 接单 | 新增 job type：`collect-pathway-data`、`generate-pathway-reports`、`publish-pathway-reports`、`record-access-decision`、`reconcile-offline-work` | 名称按任务语义命名，与其它 job type 的命名风格一致 |
| 6 | `P12_Activity_1cxqg1o`、`P12_Activity_0iv7m7o`、`P13_Activity_0wdxagy`、`P13_Activity_19r74id`、`P13_Activity_1t7mm3x`、`P13_Activity_0aygpgf`、`P13_Activity_0ccs70w` | job type 是占位值（`send` / `pass information` / 节点 id） | 改为：`refer-clinical-enquiry`、`refer-finance-enquiry`、`send-letter-to-secretaries`、`distribute-approved-letter`、`return-letter-to-consultant`、`send-consultant-reminders`、`escalate-letter-to-manager` | 同上 |

### 4.3 可复现与边界声明

```bash
# 从原始模型重新生成修复后的模型（幂等：再跑一次不会再改）
cp _analysis/original-1-14named.bpmn.keep 1-17.bpmn
python _analysis/repair_model.py          # 输出 20 处改动
```

* 已验证：脚本从原始文件重跑，产出的模型与当前 `1-17.bpmn` **逐字节相同**。
* 未改动：forms 与 formId、`zeebe:ioMapping`、网关条件表达式、lane、消息定义与消息流、
  `bpmndi` 图形坐标、文本注释、`.process-application`。文件保持 LF 行尾（原文件就是 LF）。

---

## 5. Worker 工程（`java/`）

### 5.1 技术栈与结构

照搬课程参考项目（`Messgae Example/java`）：Spring Boot 4.0.5 + `camunda-spring-boot-starter` 8.9.0 +
`@JobWorker` 注解，`application.properties` 指向本机自管集群。测试用 `camunda-process-test-spring` 8.9.0。

```
java/
├── pom.xml
├── README.md
├── WORKER_MAP.md
└── src/
    ├── main/java/io/camunda/demo/hospital/
    │   ├── HospitalPathwayApplication.java
    │   ├── config/SimulationProperties.java        模拟开关
    │   ├── support/{ExternalPartyMessenger,HospitalMessages,Variables}.java
    │   ├── simulation/{ExternalEventService,PatientAutoResponder,ScenarioRunner}.java
    │   └── worker/  (10 个类，30 个 @JobWorker)
    ├── main/resources/application.properties
    └── test/java/io/camunda/demo/hospital/{BpmnModelCoverageTest,HospitalPathwayProcessTest}.java
```

### 5.2 覆盖范围

| 维度 | 数量 | 说明 |
| --- | --- | --- |
| 自动化步骤（serviceTask + sendTask + 消息抛出事件） | 31 | 全部有 worker |
| 去重后的 job type | 30 | `send-referral-outcome` 被两个元素共用 |
| 模型等待的消息订阅 | 29 | 全部可由本工程发布 |
| 人工任务（userTask） | 116（可执行流程内） | **不在范围内**，仍由 Tasklist + 15 个 deployment-bound 表单承担，表单未做任何修改 |

元素 ↔ job type ↔ worker 方法的完整对照表见 `java/WORKER_MAP.md`。

### 5.3 两个关键设计（都是跑起来才发现的）

**（1）应答消息与入口消息的 TTL 语义不同。**
`ExternalPartyMessenger` 对两类消息区别对待：

* **入口事件**（转诊包、患者提问、退款案、监控请求、中断报告等 11 条）用 `withoutCorrelationKey()` 且
  **不设 TTL**：它们的订阅在模型部署那一刻就存在，缓冲没有意义；而被缓冲的消息只在订阅被"创建"时才会关联，
  对一个本就存在的订阅永远不会触发。
* **医院请求的应答**（排班可用性、保险决定、支付结果、退款结果、信函回复等）**带 15 分钟 TTL**：
  模型总是先发请求、后到"接收任务"，应答需要被引擎缓冲，等订阅出现时立即关联；没有分支走到的应答
  到期自动丢弃。

**（2）correlation key 必须与模型一致。**
`referralId`（转诊相关）、`patientId`（P13 信函与检验消息）、`incidentReference`（服务恢复），
其余入口消息无关联键。`BpmnModelCoverageTest` 里有一项专门比对模型与代码的关联键，写错就红。

### 5.4 模拟开关（只影响 worker 返回值，不动模型）

`application.properties` 里可切换分支：`scenario`（启动即触发的 demo 场景）、`slot-availability`、
`patient-decision`（accept/alternative/decline）、`patient-consent-decision`、
`treatment-services-available`、`funding-authorised`、`payment-outcome`（paid/failed）、
`prior-charge-found`、`refund-outcome`、`test-results-fit-to-continue`、
`retry-attempts-before-success`（让"等待名单 → 定时器重试"的循环有限次后成功，不会死循环）、
以及若干 `default-*` 标识符。完整表格见 `java/README.md`。

---

## 6. 验证与证据

### 6.1 测试：`mvn -o test` → 12/12 通过

```
Tests run: 7, Failures: 0, Errors: 0 -- in BPMN model and workers fit together
Tests run: 5, Failures: 0, Errors: 0 -- in The workers run the merged pathway on a Camunda 8 cluster
Tests run: 12, Failures: 0, Errors: 0
BUILD SUCCESS
```

* `BpmnModelCoverageTest`（不需要集群）：① 每个 service/send 任务都有可用 job type；② 每个 job type 都有 worker；
  ③ 没有孤立 worker；④ 模型等待的每条消息都能被发布；⑤ 关联键与模型一致；
  ⑥ 模型引用的每个 form 都能在文件夹里找到对应 `.form`；
  ⑦ 整棵树里 `.bpmn` 只有一个、form id 不重复（防止副本混进流程应用把部署搞挂，见第 9 节）。
  第 ① 项正是防止修复被回退的守门测试。该测试不再写死模型文件名：它在目录树里找 `.bpmn`，多于一个就直接报错。
* `HospitalPathwayProcessTest`（真实集群，remote 模式）覆盖：P15 报表路径走完、P16 权限路径（两个人工任务 + 审计 worker）、
  转诊录入 + **缺件往返**（send task 发消息 → 接收任务关联到）、转诊被拒并回话给转诊方、
  以及"模拟入口消息能启动实例"的回归项。

### 6.2 真实集群状态（REST 查询）

demo 运行后（`--hospital.simulation.scenario=monitoring`）：

```
process instance 2251799813687141  COMPLETED  incident=false
element instances: P15_Start_Monitor / P15_Task_Collect / P15_Task_Generate / P15_Task_Publish / P15_End_Report  COMPLETED
```

worker 日志（节选，来自测试与独立运行）：

```
ReferralWorkers        : referral REF-MISSING-…: asking the referring organisation for 'referral letter' (job 2251799813685416)
ExternalPartyMessenger : external party answered 'Requested referral documents' (correlationKey=REF-MISSING-…)
ReportingWorkers       : monitoring run: collected the pathway and exception data set DS-2251799813685385 (job …)
ReportingWorkers       : monitoring run: published reports RPT-REFERRALS; … with role-based access (job …)
IdentityAccessWorkers  : identity STAFF-1: access decision audited as AUD-2251799813685437 (job …)
```

独立启动时 30 个 worker 全部注册（`Starting job worker: … with type …` 共 30 行）。

### 6.3 调试过程中值得记录的三个坑

1. **`camunda-process-test` 会在测试前后清空集群**（部署和实例都没了）。我最初独立跑应用时"消息发送成功但
   毫无反应"，原因就是模型已被测试清掉；而**引擎对没人等待的消息是静默接受的**（REST 也返回 200 和一个
   messageKey），所以看起来像成功。→ 结论：跑 demo 前必须确认模型已部署；测试跑完要重新部署。
2. **默认 `managed` 测试运行时需要 Docker**，本机没有 → 必须显式配 `runtime-mode=remote` 指向 127.0.0.1。
3. **带 TTL 的消息不会启动流程实例**（只会被缓冲等待订阅创建）→ 入口消息必须不带 TTL，见 5.3(1)。

---

## 7. 已知限制与注意事项

* **userTask 不在 worker 范围内**：116 个用户任务仍由 Tasklist 完成，表单原样未改；进程测试里是用
  `completeUserTask(...)` 模拟人工提交的。
* **测试会清空集群**：跑完 `mvn test` 后需要重新部署（我已重新部署好当前版本：`Process_Hospital_Merged`
  **v1** + 15 个 form，deploymentKey `2251799813685449`，resourceName `1-17.bpmn`）。
* **整棵树里只能有一个 `.bpmn`**：这个文件夹是 Camunda Modeler 的**流程应用**（根目录有 `.process-application`），
  部署时会把树里**所有** `.bpmn`/`.form` 一起发出去；两个都定义 `Process_Hospital_Merged` 的模型会被引擎拒绝
  （`Duplicated process id in resources`），未修复的副本会因 `bpmn:group` 的 `name` 属性直接让整次部署 XSD 校验失败。
  原件因此存成了 `_analysis/original-1-14named.bpmn.keep`；测试已把这两条做成断言（见第 9 节）。
* **重试循环**：`R3_Waitlist → R3_RetryWait(P1D)`、`R5_KeepPending → R5_RetryService`、`R7` 支付重试等是模型自身的设计；
  我把重试次数做成配置（默认 1 次即成功），否则 demo 会卡在等待定时器上。
* **`retries="3"` 等模型里原有的重试属性、作业超时等未做额外配置**，沿用 starter 默认值。
* **模拟数据是固定假数据**（如 `DOC-REF-1`、`BKG-REF-…`），只用于演示；未写入任何真实的支付/证件信息。
* 文档语言：仓库原有文档是英文，我把工程说明写成英文（`README.md`、`java/README.md`），
  中文说明集中在本文件，避免同一份内容维护两遍。

---

## 8. 如果还要继续做

1. ~~**端到端 happy path 测试**~~ ✅ **已完成（第 6 轮，见第 12 章）**：`_analysis/walkthrough_driver.py` 会把一条实例从流程 1 一路驱动到 `End_TreatmentStopped`
   （18 个人工任务 + 应用接住全部外部等待，实测 86 秒、incident 0）。还没做的是把它接进 CI。
2. **Tasklist 人工流程走查**：部署后从 Tasklist 起一条转诊，人工点完各阶段；自动应答已**默认开启**，
   用默认 id（`REF-1001` / `PAT-1001`）起实例，患者/检验侧消息会自己到达，演示中途不需要手工发任何消息。
3. ~~**P13 信函三处定时器（7 天/23 天/2 个月）的加速验证**~~ ✅ **已完成（第 7 轮，见第 13 章）**：
   `_analysis/timer_demo.py` 生成一份缩短时长的模型副本（写在 bundle 之外）并部署，三个定时器全部实测触发
   （45 秒档，升级链走到高级复核）；演示后 `restore` 即回到真实时长。
4. **把 job worker 部署成独立进程/容器**，或把 `java/` 接入 CI（`mvn test` 里那 6 个模型一致性测试无需集群，适合当门禁）。

---

## 9. 补充改动（第 2 轮：集群切换 + 模型改名 + Modeler 部署失败排查）

### 9.1 集群切到课程压缩包里的 c8run

| 步骤 | 命令（在各自目录下执行） | 结果 |
| --- | --- | --- |
| 停旧副本 | `c8/c8run-8.9.0`：`.\c8run.exe stop` | `All services have been stopped.` |
| 确认端口释放 | `netstat -ano \| findstr "26500 8080 9600"` | 无监听 |
| 启新副本 | `camunda8-getting-started-bundle-8.9.0-windows-x86_64/c8run-8.9.0`：`.\c8run.exe start` | `All processes are running and healthy`；首次启动生成自己的 `camunda.process` / `camunda-data` |
| 重新部署 | 同第 3 节的部署命令 | HTTP 200，`Process_Hospital_Merged` + 15 个 form |

新安装是**独立的 H2 库**，旧集群里的部署不会跟过来，所以切换后必须重新部署 —— 已经重新部署并跑完整测试。
旧副本的数据（`c8/c8run-8.9.0/camunda-data`）没有删除，想切回去随时能启，只是不能和新副本同时开（端口完全相同）。

### 9.2 模型文件改名：`1-14named.bpmn` → `1-17.bpmn`

你在 Modeler 里把模型另存成了 `1-17.bpmn`，内容就是第 1 轮的修复产物（逐字节比对只差文件末尾一个换行）。
于是工程里所有引用都跟着改名，共 9 个文件 34 处：4 份 README、`WORK_REPORT.md`、`java/WORKER_MAP.md`、
`HospitalPathwayApplication.java`、`HospitalMessages.java`、`_analysis/repair_model.py`。
改名动作由 `_analysis/rename_model_references.py` 完成（可重放，脚本会保护 `_analysis/original-1-14named.bpmn.keep` 这个备份名）。

同时把测试从「写死文件名」改成「在文件夹树里自动发现模型」，新增 `java/src/test/java/io/camunda/demo/hospital/support/Bundle.java`，
`pom.xml` 里那段把 `*.bpmn`/`*.form` 复制进测试 classpath 的配置随之删除：测试现在直接读文件夹里的真文件，
不再有第二份副本可能过期。副作用是**目录树里多于一个 `.bpmn`、或某个 form id 重复，都会直接测试失败** ——
这正是第 9.3 节那条报错的守门断言。

### 9.3 Modeler 部署为什么失败（真实原因）

Modeler 的部署请求（`POST /v2/deployments`）返回 400：

```
INVALID_ARGUMENT: Expected to deploy new resources, but encountered the following errors:
'original-1-14named.bpmn': Error: URI=null Line=111: cvc-complex-type.3.2.2: 元素 'bpmn:group' 中不允许出现属性 'name'。
Duplicated process id in resources '1-17.bpmn' and '1-14named.bpmn'
```

**报错里的资源名不是根目录那个模型，而是 `_analysis/original-1-14named.bpmn`** —— 你交付的原始模型（未修复、两个
`bpmn:group` 上带 `name`）。为什么会把它发出去？因为：

> **这个文件夹是 Camunda Modeler 的「流程应用」（process application）**：根目录有 `.process-application` 标记文件。
> Modeler 部署时会把**整棵目录树里所有 `.bpmn` / `.form`** 一起发出去 —— 这也是 15 个表单能自动跟模型一起部署的原因。

从 Modeler 自己的日志（`%APPDATA%\camunda-modeler\logs\log.log`）里可以数出它那次一共发了 **32 个资源**：

```
1-17.bpmn                                     ← 要的
01..16_receive_referral.form ... ×15          ← 要的（部署绑定表单）
_analysis\original-1-14named.bpmn             ← 未修复的原件 → XSD 校验失败
java\target\test-classes\*.form ×15           ← 旧构建残留的副本
```

所以失败与我的模型修复无关（同一份 `1-17.bpmn` 用 curl 提交是 HTTP 200），是**树里混进了未修复的副本**：

| 报错 | 原因 | 处理（已做） |
| --- | --- | --- |
| `cvc-complex-type.3.2.2 ... bpmn:group ... name`（第 111 行） | `_analysis/original-1-14named.bpmn` 是**未修复**的原件，第 111 行正是它带 `name` 的 `P15_Group_Monitoring`，被流程应用规则一起发了出去 | 原件改名成 `_analysis/original-1-14named.bpmn.keep`（非 `.bpmn` 后缀 → 不再被当作可部署资源），内容一字未动 |
| `Duplicated process id in resources '1-17.bpmn' and '1-14named.bpmn'` | 当时树里有两个都定义 `Process_Hospital_Merged` 的模型（现在已只剩一个） | 保留唯一一个 `.bpmn` |
| （潜在）表单重复版本 | `java\target\test-classes\*.form` 是 pom 早期「复制资源到测试 classpath」留下的**旧构建副本** | 删掉；pom 里那段复制配置本轮也已移除，`mvn clean` 后不会再生成 |

复现/验证（都实测过）：

```
# 1) 树里只有 1-17.bpmn + 15 个 form 时，部署成功（命令同第 3 节） → HTTP 200
# 2) 把两个同 process id 的模型塞进同一次部署 → 复现 duplicate 报错
cp 1-17.bpmn same-process-id-copy.bpmn
curl -s -X POST http://localhost:8080/v2/deployments -F resources=@1-17.bpmn -F resources=@same-process-id-copy.bpmn
# → 400 Duplicated process id in resources 'same-process-id-copy.bpmn' and '1-17.bpmn'
```

两条守卫断言已写进测试（`BpmnModelCoverageTest`）：**整棵树里只能有一个 `.bpmn`**、**同一个 form id 在树里只能出现一次**，
以后再往目录里丢副本，`mvn test` 会先红，而不是等到 Modeler 部署时才炸。

另外：Modeler 的 Problems 面板里那一大串 `template(id: <io.camunda.connectors.aws...>): missing property name
for condition` / `invalid property type "Configuration"` 是 **Modeler 自带连接器模板**的校验警告，跟你的模型无关，
也不阻止部署；真正阻止部署的只有上面那条 `INVALID_ARGUMENT`。

### 9.4 本轮验证与未改动项

```
mvn -o test  →  Tests run: 12, Failures: 0, Errors: 0, BUILD SUCCESS   （7 个模型一致性 + 5 个真实集群）
部署          →  HTTP 200, deploymentKey 2251799813685449, Process_Hospital_Merged v1 + 15 个 form
```

本轮**没有动** `1-17.bpmn` 的模型内容（只做了逐字节比对）、15 个 `.form`、`Form_Task_Mapping.csv`、
`java/WORKER_MAP.md` 里的映射关系、`application.properties` 的模拟开关、`.process-application` 标记本身。

### 9.5 人工测试文档与新增的分析脚本（第 3 轮）

| 文件 | 内容 |
| --- | --- |
| `MANUAL_TEST_GUIDE.zh-CN.md` + `MANUAL_TEST_GUIDE.md` | 人工走查全流程（中英对称，表格行 118/118、`##` 标题 10/10）：前置检查、两种测试模式、两个入口与启动变量、主路径逐步表、全部网关的分支速查表、消息清单（关联键 + payload 必带变量）、全手工完成作业的 REST 命令、排查表、模型缺口、实测记录 |
| `_analysis/extract_walkthrough.py` → `_analysis/model-walkthrough.txt` | 从模型自动导出「每个阶段内部元素 + 作业类型 + 消息 + 关联键 + 变量写入」的原始清单；文档里的表都源自它，可重放 |
| `_analysis/audit_branch_variables.py` | 对每个网关条件变量列出「谁写它 / 表单里有没有对应字段 / Java 里有没有引用」，用来找出走不到的分支 |

实测可用的 REST 通道（文档里给了可直接粘贴的命令）：`POST /v2/messages/publication`（发消息）、
`POST /v2/jobs/activation` + `POST /v2/jobs/{key}/completion`（手工完成作业）、`POST /v2/user-tasks/search`、
`POST /v2/incidents/search`、`POST /v2/element-instances/search`。

文档里点名的两个模型缺口（这次审计发现，未修）：
1. **`caseDecision` 没有任何地方赋值** —— `P10_Gateway_Decide` 读 `= caseDecision = "re-book"` / `"clinical-review"`，
   但表单 `10_appointment_outcomes` 没有该字段、模型里没有 ioMapping、Java 里也没有 → P10 只能走默认的「通知转诊方」。
2. **`informedConsent`、`allServicesAvailable`、`fundingAuthorised`、`priorChargeFound` 不是表单字段** ——
   必须由外部消息 payload 提供（`priorChargeFound` 由 job `check-payment-idempotency` 的 worker 写入）。
   走查时若不按 §6 的模板带上这些变量，对应网关只会走默认分支。

---

## 10. 第 4 轮：起始事件对齐案例（入口回到流程 1）

### 10.1 问题

交付的合并模型顶层**只有一个普通起始事件** `P8_Start_CycleDue`（流程 8），流程 1–7 的入口都是消息起始事件。
于是 Operate / Tasklist / Modeler 里任何「Start instance / Start process」（不带 `startInstructions`）都会落进流程 8：
现象是「一启动就停在『Carry out clinical review…』」，而且该路径的等待点 `P8_Catch_Results` 关联键是 `= patientId`，
新建实例没有这个变量 → 立刻 incident（`EXTRACT_VALUE_ERROR`）。

### 10.2 改动（模型，脚本 `_analysis/repair_start_events.py`，日志 `_analysis/repair-start-events-log.txt`）

| # | 元素 | 改动 | 依据 |
| --- | --- | --- | --- |
| 1 | `Start_Tasklist` | 消息起始事件 → **普通起始事件**（成为模型唯一的默认入口） | 案例流程 1「Receive Referral」1.1：医务秘书把转诊登记进医院系统，属人工发起 |
| 2 | `P8_Start_CycleDue` | 普通起始事件 → **消息起始事件** `Next treatment cycle due` | 案例流程 8「Evaluate Whether to Continue Treatment」8.1：下一轮治疗前的检查，属医院内部/系统触发；写法与流程 9–16 一致 |
| 3 | `Message_TasklistReferral` | 删除（不再被引用） | 与第 1 项配套 |

模型其余部分仍未动（表单、formId、io 映射、网关、条件、泳道、消息流、图形布局），文件保持 LF；
脚本幂等（重跑输出「没有需要改的地方」）。改动前的模型留了一份 `_analysis/before-start-event-repair.bpmn.keep` 便于比对。

### 10.3 改动（worker 工程）

| 文件 | 改动 |
| --- | --- |
| `HospitalMessages.java` | 去掉 `TASKLIST_REFERRAL_ENTRY`；新增 `TREATMENT_CYCLE_DUE = "Next treatment cycle due"`、`PATHWAY_PROCESS_ID = "Process_Hospital_Merged"` |
| `ExternalPartyMessenger.java` | 新增 `startInstance(processId, variables)`：从普通起始事件创建实例 |
| `ExternalEventService.java` | `startReferralFromTasklist` 改为**直接创建实例**；新增 `notifyTreatmentCycleDue` |
| `ScenarioRunner.java` | 新增场景 `cycle-due`（发布 `Next treatment cycle due`，从流程 8 起实例） |

`BpmnModelCoverageTest` 会校验「模型里的每条消息都能被工程发布」，所以模型加/删消息必须同步改代码 —— 这次正是靠它兜底。

### 10.4 验证

```
部署                → HTTP 200, Process_Hospital_Merged v1 + 15 form
普通 Start instance → POST /v2/process-instances（不带 startInstructions）
                      元素轨迹 Start_Tasklist → Phase_ReceiveReferral → R1_Start → R1_RegisterCheck（= 流程 1）
P8 消息入口        → 发布 "Next treatment cycle due" → 新实例轨迹 P8_Start_CycleDue → P8_Task_Review
Tasklist            → 同时出现 R1_RegisterCheck（实例 …85367）与 P8_Task_Review（实例 …85394）
incident            → 0 条
mvn -o test         → 12/12 通过（7 模型一致性 + 5 真实集群）
```

文档同步：两份 README 的模型修复表加了第 7 行；`MANUAL_TEST_GUIDE.md` / `.zh-CN.md` 第 3 节改成「Start instance 现在直接进流程 1」、
消息清单把 `Tasklist referral entry` 换成 `Next treatment cycle due`、第 9 节标注该问题已修复、第 10 节补了本轮实测；
两份 `java/README` 的场景列表加了 `cycle-due`。

---

## 11. 第 5 轮：自动应答默认开启（以「演示能走完流程」为首要目标）

### 11.1 改了什么

| 文件 | 改动 |
| --- | --- |
| `java/src/main/resources/application.properties` | `hospital.simulation.auto-responder-enabled` 由 `false` 改为 **`true`**（默认开启），注释同步改写为「演示时由应用扮演患者/实验室/外部系统」 |
| `MANUAL_TEST_GUIDE.md` / `MANUAL_TEST_GUIDE.zh-CN.md` | ① 第 2 节从「两种模式（半自动/全手工）」改为「演示模式：只要跑起应用」；② 第 3 节改为「界面点 Start instance，不发消息」，只给变量 JSON；③ 第 4 节每个等待点标注「自动应答 / worker 自动发」；④ 第 6 节说明整张消息表都由应用发出；⑤ 原第 7 节「模式 B：手工完成 job」与第 6 节末尾的消息模板合并为**附录 A：手工调试命令（演示流程不需要）**；⑥ 第 8/9/10 节排查与实测改用「默认 id」口径并补本轮实测 |
| `java/README.md` / `java/README.zh-CN.md` | 配置开关表：auto-responder 默认值改为 `true`、注明「演示不需要手工发消息」；`scenario=none` 的说明改为「用默认 id 起实例即可一路点下去」 |
| `WORK_REPORT.md` | 本章（第 11 章）+ 第 8 章第 2 条改为「自动应答已默认开启」 |

### 11.2 为什么这么改

用户要求：**默认打开自动应答、取消手工补消息的流程**，演示优先。
默认关闭（`false`）时，流程 3 / 4 / 8 / 16 四个等待点（患者偏好、患者同意、检验结果、服务恢复）没有任何发送方，
演示必须在流程中途停下来手工发 REST 消息才能继续 —— 这与「演示完整流程」的目标冲突。
开关本身、消息内容、关联键都没变，只是把默认值反过来，并把手工命令降级为附录。

### 11.3 验证（真实命令输出）

1. `mvn -o test` → **12/12 通过**（`HospitalPathwayProcessTest` 里显式设 `auto-responder-enabled=false` 保证确定性；
   `SimulationProperties` 的字段默认值也一并改为 `true`，与 properties 保持一致）
2. 不带任何参数启动应用：`mvn spring-boot:run` → 日志 `Started HospitalPathwayApplication in 1.51 seconds`
3. 发 P8 启动消息（带默认 id）→ 新实例 `2251799813685955` 落在 `P8_Task_Review`
4. 只手工完成 `P8_Task_Review`（taskKey `2251799813685975`，HTTP 204），**全程不发任何消息**，等一个自动应答周期：

```
P8_Start_CycleDue    COMPLETED  15:22:17.682 → 15:22:17.682
P8_Task_Review       COMPLETED  15:22:17.682 → 15:22:52.967
P8_Catch_Results     COMPLETED  15:22:52.967 → 15:22:52.967   ← 自动应答的消息当场关联
P8_Task_Evaluate     ACTIVE     15:22:52.967                  ← 现在停在这个人工任务
incident 数: 0
```

5. 重新部署 `1-17.bpmn` + 15 form → **HTTP 200**

### 11.4 注意（本轮没有改变的事实）

- 自动应答固定用**默认 id**（`REF-1001` / `PAT-1001`）。实例 id 若是别的值（例如 `PAT-CYCLE-1`），消息按「名字 + 关联键」匹配不上，
  该实例仍然会停在等待点 —— **演示请用默认 id 起实例**。
- 自动应答只代发**没人请求**的四条（患者偏好、患者同意、检验结果、服务恢复）；**有请求**的应答（补件、档期、外部服务、保险、付款、退款、信函）
  一直由对应 worker 发送，本轮未改动。
- 「手工补消息」的命令**没有删除**，只是移到指南附录 A 并标注「演示不需要」，保留给调试与 P8/P9 支线。
- 第 7 章列出的两处模型缺口（`caseDecision` 无赋值、四个判定变量不是表单字段）本轮**未修**。

### 11.5 本轮没有动的文件

`1-17.bpmn`（模型零改动）、15 个 `.form`、`Form_Task_Mapping.csv`、`.process-application`、
`_analysis/` 下的全部修复脚本与 `.keep` 备份、`java/src/main/java/**`（Java 代码零改动，只改了 properties 与文档）、`java/WORKER_MAP.md`。

---

## 12. 第 6 轮：端到端自动走查（一条实例走完整条路径）

### 12.1 新增资产

`_analysis/walkthrough_driver.py`（约 250 行，只用 Python 标准库 + REST v2）：驱动一条真实实例，
**只完成人工任务**（带合理的表单值），其余全部交给运行中的应用（job worker + 自动应答）。

```bash
cd _analysis
python walkthrough_driver.py                 # 新建实例（默认 id REF-1001 / PAT-1001）并走完
python walkthrough_driver.py 2251799...      # 接管一条正在运行的实例
# 输出同时写入 _analysis/walkthrough-run.txt
```

### 12.2 实测（2026-09-27 15:31:26 → 15:32:52，集群/应用/模型都在位）

| 项目 | 结果 |
| --- | --- |
| 脚本完成的人工任务 | **18** 个：`R1_RegisterCheck` → `R2_Review` → `R2_Route` → `R3_RecordAvailability` → `R4_Assess` → `R4_Explain` → `R4_RecordConsent` → `R5_ReviewPlan` → `R5_ConfirmTreatment` → `R6_ClassifyFunding` → `R6_RecordAllocation` → `R7_CalculateCharge` → `R7_RecordPayment` → `Confirm_Treatment_Booking` → `Deliver_Treatment_Cycle` → `R8_ClinicalReview` → `Finance_RefundReview` → `Finance_RecordRefund` |
| 应用自动完成的 | 全部 service/send 作业，以及全部外部应答：患者偏好、档期可用性、患者确认、患者同意、外部服务可用性、资金授权、付款结果、退款结果 |
| 手工消息 | **0 条**（全部由 worker / 自动应答发出） |
| 最终状态 | `COMPLETED`，末元素 `End_TreatmentStopped` |
| incident | **0** |
| 用时 | ≈86 秒（每处患者/检验等待消耗一个 10 秒应答周期） |

### 12.3 说明与限制

- 脚本用**默认 id**（`REF-1001` / `PAT-1001`）起实例 —— 自动应答只认这两个默认值。
- `R8_ClinicalReview` 填 `clinicalDecision = "stop"`（走退款分支）实例才能结束；改成 `continue` 会回到阶段 5 形成下一周期循环。
- **未覆盖**：P9–P16 卫星流程（各自需要消息启动，属支线）、`P13` 的三个定时器（7 天 / 23 天 / 2 个月）与 `Timer_Reassessment`（remote 模式不能改时钟）、Tasklist 在真实身份/权限下的可见性（c8run 无鉴权）。
- 该脚本不代替人工走查：手册（`MANUAL_TEST_GUIDE`）仍是「照表单点一遍」的版本，脚本只是把「能不能走通」变成可重复的自动证明。

### 12.4 本轮没有动的文件

`1-17.bpmn`（模型零改动）、15 个 `.form`、`java/src/main/java/**`（零改动）、`java/` 的测试与 pom；只新增了脚本与 `walkthrough-run.txt`，并更新两份指南（新增第 11 节 + 第 10 节一行）与本章。

---

## 13. 第 7 轮：消息启动支线与定时器的演示工具

### 13.1 新增资产

| 文件 | 作用 |
| --- | --- |
| `_analysis/satellite_demo.py` | 发一条**消息启动事件**（流程 9–16 各一条），自动找到新实例并报告它停靠在哪个人工任务；默认报告后取消实例，`--keep` 保留给人点 |
| `_analysis/timer_demo.py` | 定时器演示工具：`build` 生成缩短时长的模型副本（写在 **bundle 之外** `bpmn/_timer-demo/1-17-fast-timers.bpmn`），`deploy` 部署、`status` 看版本、`restore` 恢复真实模型 |
| `_analysis/walkthrough_driver.py`（增强） | 新增 `--only <elementIds>`（只点指定任务、其余留给人）与两个演示旋钮：`HOSPITAL_CLINICAL_DECISION`、`HOSPITAL_REASSESSMENT_DELAY`；任务表补齐流程 9–16 的全部人工任务 |
| `_analysis/check_form_inputs.py` | 表单等价性核对：驱动器发的变量 vs 模型的 `formDefinition` + 15 个 `.form`（结果 0 处不匹配） |
| `bpmn/_timer-demo/`（bundle 之外） | 演示副本的存放目录 —— 放在 bundle 外，`BpmnModelCoverageTest`（「目录里只能有一个 .bpmn」）与 Modeler 流程应用部署都看不到它 |

### 13.2 实测证据（2026-09-27）

**① 九条消息启动全部落在模型规定的位置**（`python satellite_demo.py all`）：

| 用例 | 启动消息 | 停靠/结果 |
| --- | --- | --- |
| p9 | `TreatmentChangeRequestMessage` | `P9_Task_AdjustSchedule`（`requestStatus=approved` 分支） |
| p10 | `AppointmentCancellationReportMessage` | `P10_Task_Record` |
| p11 | `RefundCaseReceivedMessage` | `P11_Task_Decide` |
| p12 | `PatientQuestionMessage` | `P12_Activity_13m3bda` |
| p13 | `ClinicVisitCompletedMessage` | `P13_Activity_0az5u78` **+** `P13_Event_1o398fk`（Wait 7 days，两条并行分支） |
| p14 | `FollowUpRequestedMessage` | `P14_Task_Receive` |
| p15 | `Monitoring run or report request` | **COMPLETED**（全自动，约 0.15 秒） |
| p16-access | `Staff identity or access change request` | `P16_Task_Authenticate` |
| p16-interruption | `System or external-service interruption` | `P16_Task_RecordInterruption` |

**② 流程 13 的三个定时器全部触发**（快定时器副本，45 秒档）：

```
16:01:56  起：P13_Activity_0az5u78（草稿）+ P13_Event_1o398fk（Wait 7 days）     两条并行令牌
16:02:42  P13_Event_1o398fk 触发 -> P13_Gateway_0r6f1k1（letterComplete=false）-> 记录逾期信函
16:02:44  每周提醒顾问（自动）-> P13_Event_13dkl6t（Wait 23 days）
16:03:30  P13_Event_13dkl6t 触发 -> P13_Activity_0ccs70w 升级行政经理（自动）
16:04:15  P13_Event_0vf9q3w（Wait 2 months）触发 -> P13_Activity_0ba3vii 升级高级管理层
16:04:17  P13_Task_SeniorReview 复核完成 -> P13_Event_1ljjocr（升级分支结束）
```

（升级分支结束时信函分支仍开着，实例停在 `P13_Activity_1xgw427`，符合模型设计。）

**③ 阶段 8 的复评定时器不用改模型也能看见触发**：`Timer_Reassessment` 的时长是变量 `= reassessmentDelay`。
表单填 `PT20S`（或跑 `HOSPITAL_CLINICAL_DECISION=delay HOSPITAL_REASSESSMENT_DELAY=PT20S python walkthrough_driver.py --wait 115`）：

```
R8_ClinicalReview        COMPLETED  16:06:51.739 -> 16:06:52.851
Gateway_ClinicalDecision COMPLETED  16:06:52.851
Timer_Reassessment       COMPLETED  16:06:52.851 -> 16:07:13.175   ← 20.3 秒后触发
R8_ClinicalReview        COMPLETED  16:07:13.175 -> 16:07:14.242   ← 回到流程 8，开始下一轮
变量 reassessmentDelay = "PT20S"
```

**④ 「Tasklist 里人工点」与「脚本自动点」等价**（`python check_form_inputs.py`）：
驱动器为 68 个带表单的用户任务发出的变量逐个与 15 个 `.form` 核对 —— **0 处不匹配**；
最初发现 13 处「脚本用了表单没提供的选项值」（如 `caseEventType='cancellation'` 而表单是 `cancelled`），
已把脚本改为表单真实选项，并确认这 13 个字段**不被任何网关条件引用**（模型里全部条件只引用 30 个变量：
`documentsComplete, referralSuitable, slotAvailable, appointmentAccepted, informedConsent, allServicesAvailable,
externalAuthorisationRequired, fundingAuthorised, fundingApproved, paymentRequired, paymentStatus, retryApproved,
priorChargeFound, providerConfirmsPaid, clinicalDecision, refundAuthorised, requestStatus, affectsChargeOrFunding,
caseDecision, paymentReceived, refundOutcome, urgentConcern, enquiryCategory, consultantApproved,
noSuspectedClinicalError, letterComplete, accessAuthorised, fitToContinue, action, alternativeDateRequested`）。
结论：**人工在 Tasklist 里按表单填，产生的分支决定与自动走查完全一致**。

### 13.3 注意

- 定时器演示用的是**最新部署版本**：演示前 `build`+`deploy`，演示后**必须** `restore`，否则集群最新版本一直是快定时器版
  （本轮版本序列：v1 真实 → v2/v3 快定时器 → v4 恢复真实）。
- 流程 13 的网关 `P13_Gateway_0r6f1k1` 读 `letterComplete = false`，而草稿任务（`P13_Activity_0az5u78`）的输出映射才写这个变量：
  **必须在第一个定时器触发前点掉草稿**（45 秒档从容，15 秒档不够），否则走默认边「停发提醒」。
  第一次用 15 秒档的尝试就是这个结果，日志留在 `_analysis/p13-timer-run-first-attempt-15s.txt`：
  「完成 `P13_Activity_0az5u78` -> 当前元素 `['P13_Activity_0az5u78', 'P13_Activity_0oe33ui']`」—— 定时器先触发、网关走了默认边。
- 启动消息**不要传** `correlationKey`；全流程/支线的自动化都依赖应用在跑（`auto-responder-enabled=true` 已是默认）。

### 13.4 本轮没有动的文件

`1-17.bpmn`、15 个 `.form`、`java/src/main/java/**`、`java/src/test/**`、`pom.xml` 均未改动；
新增 `_analysis/{satellite_demo.py, timer_demo.py}`、`_analysis/{satellite-demo-run.txt, p13-timer-run-first-attempt-15s.txt, p13-timer-run2.txt, reassessment-timer-run.txt, walkthrough-run-full-path.txt, walkthrough-run-reassessment-timer.txt}`、
`bpmn/_timer-demo/1-17-fast-timers.bpmn`；两份指南新增第 12 节 + 第 10 节三行、两份 README 更新 `_analysis/` 行。

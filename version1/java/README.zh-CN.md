# 医院患者路径的 Java Job Worker

[English](README.md) | **简体中文**

针对 [`../1-17.bpmn`](../1-17.bpmn) 中合并流程的 Camunda 8 job worker。
实现方式与课程参考项目（`Messgae Example/java`）一致：Spring Boot 4、Camunda Spring Boot Starter 8.9.0，
每个自动化步骤对应一个 `@JobWorker` 方法。

worker 承担模型中的"自动化那一半"：凡是需要人来做的，仍然是 Tasklist 里的用户任务；
凡是被模型标为服务任务、发送任务或消息抛出事件的，都由这里处理。

| 内容 | 位置 |
| --- | --- |
| 50 个 job type，覆盖 51 个自动化步骤 | `src/main/java/io/camunda/demo/hospital/worker/` |
| job type ↔ 模型元素 ↔ worker 对照表 | [`WORKER_MAP.md`](WORKER_MAP.md) |
| 外部参与方的回话（消息） | `hospital.support.ExternalPartyMessenger` |
| 医院侧步骤无法产生的事件（患者、检验、入口事件） | `hospital.simulation.ExternalEventService` |
| 模型留给人来决定的开关（配置项） | `hospital.config.SimulationProperties` |
| 测试 | `src/test/java/io/camunda/demo/hospital/` |
| H2 系统记录：schema、种子数据、JDBC 仓储 | `src/main/resources/db/`、`hospital.persistence` |
| 一次运行留下的数据库文件（不入版本库） | `data/` |
| 构建工具（项目自带，机器上无需安装 Maven） | `mvnw`、`mvnw.cmd`、`.mvn/` |
| 变更报告（模型修复、设计、验证证据） | [`../WORK_REPORT.md`](../WORK_REPORT.md) |
| 本 README 的英文版 | [`README.md`](README.md) |

## 自带 Maven（wrapper）

项目自带 Maven，机器上无需安装任何 Maven，只要有 JDK 21+（`JAVA_HOME`，或 `PATH` 上的 `java`）。

| 文件 | 作用 |
| --- | --- |
| `mvnw` / `mvnw.cmd` | 启动脚本：分别用于 Git Bash / Linux / macOS 与 Windows CMD、PowerShell |
| `.mvn/wrapper/maven-wrapper.properties` | 固定 Maven 版本（3.9.12）与下载来源 |
| `.mvn/maven.config` | 项目级 Maven 参数，wrapper 与普通 `mvn` 都会读取 |

下文命令都给出两种写法；用机器上的 `mvn` 也可以，但只有 wrapper 能保证用上被固定的那个版本。

```bash
# Git Bash
./mvnw -v                                  # Apache Maven 3.9.12，home 在 ~/.m2/wrapper/dists
./mvnw dependency:resolve                  # 解析全部声明依赖（含 test 作用域）
./mvnw "-Dtest=BpmnModelCoverageTest" test # 7/7 通过，无需集群
./mvnw spring-boot:run
```

```powershell
# PowerShell
.\mvnw.cmd -v
.\mvnw.cmd dependency:resolve
.\mvnw.cmd "-Dtest=BpmnModelCoverageTest" test
.\mvnw.cmd spring-boot:run
```

**首次**运行会把固定版本的 Maven 下载到 `~/.m2/wrapper/dists`（约 9 MB），只有这一次慢；之后
启动速度与机器上的 `mvn` 相当。若想让 IDEA 用同一个 Maven，把 `Settings -> Build Tools ->
Maven -> Maven home` 设为 *Wrapper*。

## 运行

前置条件：JDK 21+，以及一套跑在默认端口上的本地 Camunda 8.9 集群（gRPC `26500`、REST `8080`）
—— worker 连接的地址就在
[`src/main/resources/application.properties`](src/main/resources/application.properties) 里。本项目针对
c8run 8.9.0（课程压缩包里的那一版）验证过；任何本地 Camunda 8.9 都可以，集群也不必和本仓库放在一起。
**必须先部署流程模型，worker 才有活干** —— worker 的存在意义就是消费该模型产生的 job。

> 同一时间只能有一个 c8run 占用 26500 / 8080 / 9600 端口。如果别的副本已在运行，
> 请先在该目录执行 `.\c8run.exe stop` 停掉它。

**1. 启动集群**（只需一次，在你解压 c8run 的那个目录里执行）

```bash
# Git Bash
./c8run.exe start
```

```powershell
# PowerShell
.\c8run.exe start
```

**2. 把模型与它的部署绑定表单一起部署**（在本仓库根目录执行 —— 也就是放 `1-17.bpmn` 和 `.form` 的那一层
—— 确保模型和表单落在同一次部署里）

```bash
# Git Bash
curl -X POST http://localhost:8080/v2/deployments \
  $(for f in 1-17.bpmn *.form; do printf ' -F resources=@%s' "$f"; done)
```

```powershell
# PowerShell（用 curl.exe，不要用 Invoke-WebRequest 的 curl 别名）
$form = Get-ChildItem *.form | ForEach-Object { '-F'; "resources=@$($_.Name)" }
curl.exe -sS -X POST http://localhost:8080/v2/deployments -F "resources=@1-17.bpmn" @form
```

也可以直接用 Camunda Modeler 部署：打开 `1-17.bpmn`，保持 `.form` 在同目录，点 Deploy。
这个文件夹是 Modeler 的**流程应用**（`.process-application`），Deploy 会把树里所有 `.bpmn`/`.form`
一起发出去（含子目录）。目录里别留副本：多一个模型会 `Duplicated process id in resources`，
未修复的副本会过不了 XSD 校验。完整报错清单见 bundle 根目录的 `README.zh-CN.md`。

**3. 启动 worker**（在 `java/` 目录执行）

```bash
# Git Bash
./mvnw spring-boot:run
```

```powershell
# PowerShell
.\mvnw.cmd spring-boot:run
```

可选：启动时触发一次 demo 场景（取值之一：`referral`、`referral-tasklist`、`cycle-due`、`enquiry`、
`cancellation`、`treatment-change`、`refund`、`letter`、`follow-up`、`monitoring`、`identity`、
`interruption`）：

```bash
# Git Bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring
```

```powershell
# PowerShell：`-D...` 这个参数要整体加引号 —— 裸写 "-Dxx-yy=..." 会在第二个连字符处被拆开，
# Maven 于是报 `Unknown lifecycle phase ".run.arguments=..."`（在 PowerShell 5.1 实测；
# "-o"、"-Dtest=X"、"--hospital...=X" 这些没有第二个连字符的写法都能原样通过）
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring"
# 等价写法：--% 之后的内容 PowerShell 不再解析
.\mvnw.cmd --% spring-boot:run -Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring
```

`referral-tasklist` 直接创建实例（模型唯一的普通起始事件就是流程 1 的入口）；`cycle-due` 发布
「Next treatment cycle due」消息，从流程 8 起一条实例。

`scenario=none`（默认）时 worker 只是等待真实业务：可以从 Tasklist 发起实例、发布消息启动，
或在 Operate/Tasklist 里人工点击推进各条路径。

## 测试

```bash
# Git Bash
./mvnw test
```

```powershell
# PowerShell
.\mvnw.cmd test
```

* `BpmnModelCoverageTest` —— 不需要集群。它在 bundle 目录树里找模型（`Bundle.model()`；这个文件夹是 Modeler 的流程应用，
  树里出现第二个 `.bpmn` 或重复的 form id 就会让测试失败），读取 `1-17.bpmn`，在以下情况失败：
  某个服务任务/发送任务没有可用的 job type；某个 job type 没有 worker；某个 worker 没有对应的模型元素；
  模型等待的某条消息无法被发布；代码里的关联键与模型不一致；模型引用的某个 form 在文件夹里没有对应的 `.form` 文件。
* `HospitalPathwayProcessTest` —— 针对真实集群运行流程。测试框架以 **remote** 模式启动
  （默认的 `managed` 模式需要 Docker 并会拉取 Camunda 镜像），因此本机集群必须在
  `127.0.0.1:26500` / `127.0.0.1:8080` 上运行。

流程测试覆盖：报表路径（流程 15）、身份权限路径（流程 16）、转诊录入包含"缺件往返"
（发送任务 → 发布消息 → 接收任务），以及转诊被拒并以结果消息回话给转诊机构。

> 测试框架会在测试前后**清空集群**（部署与实例都会消失）。如果之后还想继续点击推进流程，
> 需要重新部署模型。

## worker 如何与流程对话

流程提出请求，worker 给出应答，流程在等待该应答的步骤继续：

| 医院侧步骤（job type） | worker 发布的消息 | 等待中的元素 |
| --- | --- | --- |
| `send-referral-documents-request`（R1_RequestDocs） | Requested referral documents | R1_ReceiveDocs |
| `request-consultation-slot`（R3_RequestSchedule） | Initial consultation slot availability | R3_ReceiveSchedule |
| `send-appointment-confirmation`（R3_BookSendOffer） | Patient appointment response / Alternative consultation date / Patient declines consultation | R3_PatientResponse / R3_AlternativeDate / R3_PatientDeclined |
| `request-treatment-service`（R5_RequestService） | Treatment or external service availability | R5_ReceiveService |
| `request-funding-authorisation`（R6_RequestAuth） | Funding authorisation decision | R6_ReceiveAuth |
| `request-payment`（R7_SendPaymentRequest） | Payment result from provider | R7_ReceivePaymentResult |
| `send-refund-request`（P11_Task_SendRequest） | 支付服务商的退款结果 | P11_Catch_RefundResult |
| `send-letter-to-secretaries`（P13） | LetterFromDoctor | P13_Activity_0mfrh8d |
| `return-letter-to-consultant`（P13） | LetterBackForRechecking | P13_Activity_0f4hol4 |
| `send-consultant-reminders`（P13） | MessageFromDoctor | P13_Activity_0m2occw |
| `escalate-letter-to-manager`（P13） | ReportFromDoctor | P13_Activity_0ae9rsk |
| `inform-referring-organisation`、`notify-patient`、`refer-clinical-enquiry`、`refer-finance-enquiry` | 无 —— 这些消息流跨越池边界，引擎不执行它们，因此 worker 只记录交接结果后完成 | - |

这里有两条规则很关键，都由 `ExternalPartyMessenger` 落实：

1. **应答消息带 TTL（time to live）。** 流程总是先发出请求、随后才走到等待应答的接收任务，
   因此引擎会缓冲应答，等订阅出现的那一刻立即关联。没有分支走到的应答会静默到期失效。
2. **入口事件不带 TTL。** 那 11 条启动流程的消息（转诊、患者提问、退款案、监控请求、中断报告……）
   在模型部署那一刻就已存在订阅，缓冲毫无意义 —— 而且被缓冲的消息只在订阅**被创建**时才会关联，
   对于一个本就存在的订阅永远不会触发。

关联键来自模型：属于同一次转诊的用 `referralId`，门诊信函与检验消息用 `patientId`，
"服务恢复"用 `incidentReference`。订阅未定义关联键的消息（流程 8–16 的启动事件）
使用 `withoutCorrelationKey()` 发布。

## 模拟外部参与方与人工决策

`ExternalEventService` 负责发布一切医院侧步骤无法产生的事件：转诊包、患者提问、取消、
治疗方案变更请求、退款案、门诊就诊完成、随访请求、监控请求、权限申请、中断报告，
以及患者的预约偏好与知情同意决定、检验结果和"服务恢复"。

`src/main/resources/application.properties` 中的开关决定流程走哪条分支。
它们都不改动模型，只改变 worker 返回或发布的内容。

| 开关 | 作用 |
| --- | --- |
| `hospital.simulation.scenario` | 启动时触发一次的事件（`none` = 只等真实业务） |
| `hospital.simulation.auto-responder-enabled` | 按固定间隔发布患者/检验侧应答（默认 `true`，演示不需要手工发消息） |
| `hospital.simulation.default-referral-id` / `default-patient-id` | 定时应答使用的 id（`REF-1001` / `PAT-1001`）—— 起演示实例时用这两个 id，应答才能关联上 |
| `hospital.simulation.slot-availability` | 排班服务的回答：`available` / `unavailable` |
| `hospital.simulation.patient-decision` | `accept` / `alternative` / `decline` |
| `hospital.simulation.patient-consent-decision` | `consent` / `refuse` |
| `hospital.simulation.treatment-services-available` | 外部治疗服务的可预约容量 |
| `hospital.simulation.funding-authorised` | 保险公司批准或拒绝 |
| `hospital.simulation.payment-outcome` | `paid` / `failed` |
| `hospital.simulation.prior-charge-found` | 幂等检查：重试支付还是转交调查 |
| `hospital.simulation.refund-outcome` | `completed` / `rejected` |
| `hospital.simulation.test-results-fit-to-continue` | 下一疗程的检验结果 |
| `hospital.simulation.retry-attempts-before-success` | 等待名单循环多少次后给出成功应答（保证重试循环有限） |
| `hospital.simulation.default-*` | 流程尚未产生标识符时，模拟参与方使用的默认标识符 |

## H2 系统记录

路径的业务对象保存在内嵌的 H2 数据库里，worker 因此是**从记录里回答**，而不是凭空编一个答案：
患者、转诊、预约与号源请求、就诊与知情同意、治疗服务与疗程、资金授权与清算、费用、退款案、
门诊信函、咨询、权限决定、监测记录、报表与审计轨迹。

| 内容 | 位置 |
| --- | --- |
| 连接、启动初始化、测试用的 H2 | `src/main/resources/application.properties`、`src/test/resources/application.properties` |
| 17 张表（显式主键与外键） | `src/main/resources/db/schema.sql` |
| 服务目录与演示用患者/转诊 | `src/main/resources/db/data.sql` |
| 每个聚合一个仓储，统一从一个门面进入 | `hospital.persistence`（`HospitalDatabase`） |
| 一次运行留下的文件 | `data/hospital-pathway.mv.db`（不入版本库） |

启动做什么、以及**刻意不做什么**：

* schema 每次启动都重放且幂等；服务目录用 `MERGE ... KEY` 重放，因此修正过的目录行能进入已存在的库。
* 演示患者 `PAT-1001` 与演示转诊 `REF-1001` **只在键不存在时插入**。接诊、审核等步骤会写它们的
  联系方式、状态与优先级，所以重启保留这次运行写入的值，而不是悄悄改回种子值 —— 演示是被**续跑**，
  不是被重置。
* 因此，要重新从种子状态开始演示，就显式删掉 `data/`。
* JVM 干净关闭文件库时，H2 会在旁边留下一个很小的 `data/hospital-pathway.trace.db`（压缩期间的
  内部空闲空间断言）。数据完好，重新打开就能看到。
* 测试跑在 `jdbc:h2:mem:` 上，因此测试永远不会写进某次运行留下的文件。

用构建时已经下好的 H2 jar 直接查看：

```powershell
$h2 = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\h2database\h2" -Recurse -Filter "h2-*.jar" |
      Select-Object -First 1 -ExpandProperty FullName
java -cp $h2 org.h2.tools.Shell -url "jdbc:h2:file:./data/hospital-pathway;AUTO_SERVER=TRUE" `
     -user sa -password "" -sql "SELECT COUNT(*) FROM referral"
```

两个保持证据可信的习惯：不要在 `java/` 里同时跑两个 Maven（第二个会破坏 `clean`）；跑文件模式测试
前先停掉应用 —— 否则 `AUTO_SERVER` 会让测试 JVM 挂到正在使用的库文件上。

## 已验证的内容

* 本机跑着 Camunda 8 集群时 `.\mvnw.cmd -B clean test` 全绿：**26 个测试** —— 7 个模型/worker
  一致性测试、7 个数据库测试、6 个 worker 持久化测试、2 个走廊消息测试，以及 4 个在集群上真正启动实例
  走完整条路径的端到端测试。没有集群时，`.\mvnw.cmd -B "-Dtest=!HospitalPathwayProcessTest" test`
  跑其中不需要集群的 22 个。流程测试中可以看到 worker 在真实地完成 job，例如：

  ```
  ReferralWorkers    : referral REF-MISSING-...: asking the referring organisation for 'referral letter' (job 2251799813685416)
  ExternalPartyMessenger : external party answered 'Requested referral documents' (correlationKey=REF-MISSING-...)
  ReportingWorkers   : monitoring run: collected the pathway and exception data set DS-2251799813685385 (job 2251799813685385)
  ReportingWorkers   : monitoring run: published reports RPT-REFERRALS; ... with role-based access (job 2251799813685453)
  IdentityAccessWorkers : identity STAFF-1: access decision audited as AUD-2251799813685437 (job 2251799813685437)
  ```

* 模型已部署的前提下执行
  `mvn spring-boot:run "-Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring"`：
  50 个 job worker 全部注册，场景启动一条实例，应用自身的 worker 把报表路径跑完。下面这段是上一版
  模型抓取的记录 —— 监控入口现在落在 inbox 捕获事件 `Inbox_P15_Start_Monitor` 上：

  ```
  process instance 2251799813687141  COMPLETED  incident=false
  element instances: P15_Start_Monitor, P15_Task_Collect, P15_Task_Generate, P15_Task_Publish, P15_End_Report  COMPLETED
  ```

* H2 系统记录（见上文《H2 系统记录》）：一次运行会留下 `data/hospital-pathway.mv.db`，内含 17 张表
  与种子的演示数据；worker 写过的值在重启之后依然存在；要把演示退回种子状态，只需删掉 `data/`。
  证据见 `../_analysis/VERIFICATION-H2-WORKERS.md`。

* Maven wrapper（见上文《自带 Maven（wrapper）》，固定 Maven 3.9.12）：
  `./mvnw dependency:resolve` 能解析全部声明依赖（含 test 作用域）；
  `.\mvnw.cmd -o "-Dtest=BpmnModelCoverageTest" test` 约 3.5 秒 7/7 通过，既不需要集群也不需要联网。

## 备注

* 这里不实现用户任务 —— 它们属于 Tasklist，bundle 目录中的部署绑定表单原样使用。
* 模型原本存在导致无法部署的缺陷；修复内容（job type、网关条件、消息订阅、`bpmn:group`）
  记录在 [`../README.zh-CN.md`](../README.zh-CN.md)，并可用 `../_analysis/repair_model.py` 重放。
* 如果你的 `PATH` 上的 `mvn` 是个坏掉的 shim（例如某个 IDE 扩展装的），它可能把自身 home 解析成
  MSYS 路径，从而报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`。
  上面的 wrapper 就是解法 —— `./mvnw` / `.\mvnw.cmd` 完全不碰那个 shim，用的是 `~/.m2/wrapper/dists`
  （Maven 自己的用户级缓存）里解开的那个 Maven。`../_analysis/mvnx.sh` 是针对这种坏 `mvn` 的更早
  绕行方案：用原生 classpath 直接调 `MVN_HOME` 指向的 Maven
  （`MVN_HOME=/path/to/apache-maven-3.9.x bash ../_analysis/mvnx.sh ...`），现在只作兜底。
* IDEA 不会自动使用 wrapper：请把 `Settings -> Build, Execution, Deployment -> Build Tools ->
  Maven -> Maven home` 设为 *Wrapper*（IDEA 自身的设置存在 `.idea/` 里，属于本机配置）。
* 本文件是 [`README.md`](README.md) 的中文版；两份内容保持一致。

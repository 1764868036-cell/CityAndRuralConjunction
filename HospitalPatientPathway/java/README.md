# Java job workers for the hospital patient pathway

**English** | [简体中文](README.zh-CN.md)

Camunda 8 job workers for the integrated pathway in [`../1-17.bpmn`](../1-17.bpmn), built the
same way as the class example (`Messgae Example/java`): Spring Boot 4, the Camunda Spring Boot
Starter 8.9.0 and one `@JobWorker` method per automated step.

The workers are the automation half of the model. Everything a person has to do stays a user task
in Tasklist; everything the model marks as a service task, a send task or a message throw event is
handled here.

| What | Where |
| --- | --- |
| 50 job types, 51 automated steps | `src/main/java/io/camunda/demo/hospital/worker/` |
| Job type ↔ model element ↔ worker table | [`WORKER_MAP.md`](WORKER_MAP.md) |
| Study guide: what each worker reads, writes and publishes (Chinese) | [`WORKER_GUIDE.zh-CN.md`](WORKER_GUIDE.zh-CN.md) |
| Answers of the external parties (messages) | `hospital.support.ExternalPartyMessenger` |
| Events no hospital step can produce (patient, laboratory, entry events) | `hospital.simulation.ExternalEventService` |
| Human decisions the model leaves open (configuration switches) | `hospital.config.SimulationProperties` |
| Tests | `src/test/java/io/camunda/demo/hospital/` |
| The H2 system of record: schema, seed data, JDBC repositories | `src/main/resources/db/`, `hospital.persistence` |
| The database file a run leaves behind (not in version control) | `data/` |
| Build tooling (carried by the project, no machine-wide Maven needed) | `mvnw`, `mvnw.cmd`, `.mvn/` |
| Change report (model repairs, design, verification - Chinese) | [`../WORK_REPORT.md`](../WORK_REPORT.md) |
| Chinese version of this README | [`README.zh-CN.md`](README.zh-CN.md) |

## Build with the wrapper

The project carries its own Maven, so nothing has to be installed machine-wide - a JDK 21+ is enough
(`JAVA_HOME`, or `java` on the `PATH`).

| File | Purpose |
| --- | --- |
| `mvnw` / `mvnw.cmd` | the launcher: Git Bash / Linux / macOS, and Windows CMD or PowerShell |
| `.mvn/wrapper/maven-wrapper.properties` | pins the Maven version (3.9.12) and where it is fetched from |
| `.mvn/maven.config` | project-wide Maven options, honoured by the wrapper and by a plain `mvn` alike |

The commands below are written twice, once per shell. A machine-wide `mvn` works too, but only the
wrapper guarantees the pinned version.

```bash
# Git Bash
./mvnw -v                                  # Apache Maven 3.9.12, home in ~/.m2/wrapper/dists
./mvnw dependency:resolve                  # every declared dependency, test scope included
./mvnw "-Dtest=BpmnModelCoverageTest" test # 7/7, no cluster needed
./mvnw spring-boot:run
```

```powershell
# PowerShell
.\mvnw.cmd -v
.\mvnw.cmd dependency:resolve
.\mvnw.cmd "-Dtest=BpmnModelCoverageTest" test
.\mvnw.cmd spring-boot:run
```

The **first** run downloads the pinned Maven into `~/.m2/wrapper/dists` (about 9 MB) and is the only
slow one; afterwards the wrapper starts as fast as a machine-wide `mvn`. To make IntelliJ IDEA use the
same Maven, set `Settings -> Build Tools -> Maven -> Maven home` to *Wrapper*.

## Run

Prerequisites: JDK 21+ and a local Camunda 8.9 cluster on its default ports (`26500` gRPC, `8080`
REST) - the addresses the workers dial are in
[`src/main/resources/application.properties`](src/main/resources/application.properties). The project
was verified against c8run 8.9.0 (the build that ships with the course bundle); any local Camunda 8.9
works, and the cluster does not have to sit next to this repository.
**The pathway has to be deployed before the workers can do anything** - the workers only exist to
serve jobs of that model.

> Only one c8run can hold the ports 26500 / 8080 / 9600 at a time. If another copy is already running,
> stop it first with `.\c8run.exe stop` in that folder.

**1. start the cluster** (once, inside whichever folder you unpacked c8run into)

```bash
# Git Bash
./c8run.exe start
```

```powershell
# PowerShell
.\c8run.exe start
```

**2. deploy the model and its deployment-bound forms together** (from the repository root - the folder
that holds `1-17.bpmn` and the `.form` files - so that model and forms land in one deployment)

```bash
# Git Bash
curl -X POST http://localhost:8080/v2/deployments \
  $(for f in 1-17.bpmn *.form; do printf ' -F resources=@%s' "$f"; done)
```

```powershell
# PowerShell (curl.exe, not the Invoke-WebRequest alias)
$form = Get-ChildItem *.form | ForEach-Object { '-F'; "resources=@$($_.Name)" }
curl.exe -sS -X POST http://localhost:8080/v2/deployments -F "resources=@1-17.bpmn" @form
```

In Camunda Modeler you can deploy instead: open `1-17.bpmn` with the `.form` files next to it and
press Deploy. The folder is a Modeler **process application** (`.process-application`), so Deploy
sends every `.bpmn` and `.form` below it - including anything in a sub folder. Keep the tree free of
stray copies: a second model makes the deployment fail (`Duplicated process id in resources`), an
unrepaired copy fails the XSD check. `README.md` in the bundle folder lists the messages.

**3. start the workers** (from `java/`)

```bash
# Git Bash
./mvnw spring-boot:run
```

```powershell
# PowerShell
.\mvnw.cmd spring-boot:run
```

Optional demo scenario, fired once on start-up (one of `referral`, `referral-tasklist`, `cycle-due`,
`enquiry`, `cancellation`, `treatment-change`, `refund`, `letter`, `follow-up`, `monitoring`,
`identity`, `interruption`):

```bash
# Git Bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring
```

```powershell
# PowerShell: quote the -D... argument - a bare "-Dxx-yy=..." is split at the second hyphen and Maven
# answers `Unknown lifecycle phase ".run.arguments=..."` (tested on PowerShell 5.1; "-o", "-Dtest=X"
# and "--hospital...=X" pass through fine)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring"
# equivalent: --% stops PowerShell parsing everything after it
.\mvnw.cmd --% spring-boot:run -Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring
```

`referral-tasklist` creates the instance directly (the model's only plain start event is the entry of
process 1); `cycle-due` publishes the `Next treatment cycle due` message and starts an instance in
process 8.

With `scenario=none` (the default) the workers just wait for real work: start an instance from
Tasklist, by publishing a message, or by clicking through the paths in Operate/Tasklist.

## Test

```bash
# Git Bash
./mvnw test
```

```powershell
# PowerShell
.\mvnw.cmd test
```

* `BpmnModelCoverageTest` - no cluster needed. It looks for the model in the bundle folder tree
  (`Bundle.model()`; the folder is a Modeler process application, so a second `.bpmn` or a duplicate
  form id below it fails the test) and reads `1-17.bpmn`, failing when a service or send task has no
  usable job type, when a job type has no worker, when a worker has no model step, when a message the
  pathway waits for cannot be published, when a correlation key in the code differs from the key in
  the model, or when a form the model references has no `.form` file.
* `HospitalPathwayProcessTest` - runs the pathway against a real cluster. The test framework is
  started in **remote** mode (the default `managed` mode would need Docker and download a Camunda
  image), so the local cluster has to be running on `127.0.0.1:26500` / `127.0.0.1:8080`.

The process tests cover the reporting path (process 15), the identity/access path (process 16), the
referral entry with the missing-document round trip (send task → published message → receive task)
and a declined referral that ends with the outcome message to the referring organisation.

> The test framework **purges the cluster** (deployments, instances) around test runs. If you want to
> keep clicking through the pathway afterwards, deploy the model again.

## How the workers talk to the pathway

The model asks for something, a worker answers, and the pathway continues at the step that waits for
the answer:

| Hospital step (job type) | Message published by the worker | Waiting element |
| --- | --- | --- |
| `send-referral-documents-request` (R1_RequestDocs) | Requested referral documents | R1_ReceiveDocs |
| `request-consultation-slot` (R3_RequestSchedule) | Initial consultation slot availability | R3_ReceiveSchedule |
| `send-appointment-confirmation` (R3_BookSendOffer) | Patient appointment response / Alternative consultation date / Patient declines consultation | R3_PatientResponse / R3_AlternativeDate / R3_PatientDeclined |
| `request-treatment-service` (R5_RequestService) | Treatment or external service availability | R5_ReceiveService |
| `request-funding-authorisation` (R6_RequestAuth) | Funding authorisation decision | R6_ReceiveAuth |
| `request-payment` (R7_SendPaymentRequest) | Payment result from provider | R7_ReceivePaymentResult |
| `send-refund-request` (P11_Task_SendRequest) | Refund result from the payment service provider | P11_Catch_RefundResult |
| `send-letter-to-secretaries` (P13) | LetterFromDoctor | P13_Activity_0mfrh8d |
| `return-letter-to-consultant` (P13) | LetterBackForRechecking | P13_Activity_0f4hol4 |
| `send-consultant-reminders` (P13) | MessageFromDoctor | P13_Activity_0m2occw |
| `escalate-letter-to-manager` (P13) | ReportFromDoctor | P13_Activity_0ae9rsk |
| `inform-referring-organisation`, `notify-patient`, `refer-clinical-enquiry`, `refer-finance-enquiry` | none - the message flow crosses the pool boundary and is not executed by the engine, so the worker records the hand-over and completes | - |

Two rules matter here, and both are enforced in `ExternalPartyMessenger`:

1. **Answers are published with a time to live.** The pathway always sends its request *before* it
   reaches the receive task that waits for the answer, so the engine buffers the answer and
   correlates it the moment the subscription exists. An answer nobody waits for (a branch that was
   not taken) expires silently.
2. **Entry events are published without a time to live.** The 11 messages that start a process
   (referral, patient question, refund case, monitoring run, interruption, ...) have a subscription
   from the moment the model is deployed, so buffering is pointless - and a buffered message only
   correlates when a subscription is *created*, which never happens for a subscription that already
   exists.

Correlation keys come from the model: `referralId` for everything that belongs to one referral,
`patientId` for the clinic-letter and laboratory messages, `incidentReference` for
"service restored". Messages whose subscription defines no key (the start events of processes 8-16)
are published with `withoutCorrelationKey()`.

## Simulated external parties and decisions

`ExternalEventService` publishes everything that no hospital step can produce: a referral package, a
patient question, a cancellation, a treatment-change request, a refund case, a completed clinic
visit, a follow-up request, a monitoring run, an access request, an interruption report, the
patient's appointment preference and consent decision, laboratory results and "service restored".

The switches in `src/main/resources/application.properties` decide which branch the pathway takes.
Nothing here changes the model; it only changes what a worker returns or publishes.

| Switch | Effect |
| --- | --- |
| `hospital.simulation.scenario` | event fired once at start-up (`none` = wait for real work) |
| `hospital.simulation.auto-responder-enabled` | publish the patient/laboratory answers on a fixed interval (`true` by default, so a demo needs no hand-published messages) |
| `hospital.simulation.default-referral-id` / `default-patient-id` | the ids the interval answers use (`REF-1001` / `PAT-1001`) - start demo instances with these ids so the answers correlate |
| `hospital.simulation.slot-availability` | `available` / `unavailable` answer of the scheduling service |
| `hospital.simulation.patient-decision` | `accept` / `alternative` / `decline` |
| `hospital.simulation.patient-consent-decision` | `consent` / `refuse` |
| `hospital.simulation.treatment-services-available` | capacity of the external treatment service |
| `hospital.simulation.funding-authorised` | insurer approves or declines |
| `hospital.simulation.payment-outcome` | `paid` / `failed` |
| `hospital.simulation.prior-charge-found` | idempotency check: retry the payment or investigate |
| `hospital.simulation.refund-outcome` | `completed` / `rejected` |
| `hospital.simulation.test-results-fit-to-continue` | laboratory results of the next cycle |
| `hospital.simulation.retry-attempts-before-success` | how often a waiting list loop runs before a slot/capacity answer succeeds (keeps the retry loops finite) |
| `hospital.simulation.default-*` | identifiers used when a simulated party publishes before the process produced one |

## The H2 system of record

The pathway keeps its business objects in an embedded H2 database, so a worker answers from a record
instead of inventing an answer: the patient, the referral, the appointments and slot requests, the
consultations, the treatment services and cycles, the funding authorisation and clearance, the
charges, the refund cases, the clinic letters, the enquiries, the access decisions, the monitoring
records, the reports and the audit trail.

| What | Where |
| --- | --- |
| Connection, start-up initialisation, H2 in tests | `src/main/resources/application.properties`, `src/test/resources/application.properties` |
| The 17 tables, with explicit keys and foreign keys | `src/main/resources/db/schema.sql` |
| The service catalogue and the demo patient and referral | `src/main/resources/db/data.sql` |
| One repository per aggregate behind a single facade | `hospital.persistence` (`HospitalDatabase`) |
| The file a run leaves behind | `data/hospital-pathway.mv.db` (not in version control) |

What a start does, and what it deliberately does **not** do:

* The schema is re-applied on every start and is idempotent, and the service catalogue is re-applied
  with `MERGE ... KEY`, so a corrected catalogue row reaches an existing database.
* The demo patient `PAT-1001` and the demo referral `REF-1001` are inserted **only when their key is
  absent**. The intake and review steps write their contact details, status and priority, so a restart
  keeps what the run wrote instead of reverting it - the demo is *resumed*, not reset.
* Deleting `data/` is therefore the deliberate way to start the demo from the seeded state again.
* H2 leaves a small `data/hospital-pathway.trace.db` behind when a JVM shuts the file down cleanly (an
  internal free-space assertion during compaction). The rows are intact; reopen the database and they
  are there.
* Tests run against `jdbc:h2:mem:`, so a test never writes into the file a run left behind.

Inspect the file with the H2 jar the build already downloaded:

```powershell
$h2 = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\h2database\h2" -Recurse -Filter "h2-*.jar" |
      Select-Object -First 1 -ExpandProperty FullName
java -cp $h2 org.h2.tools.Shell -url "jdbc:h2:file:./data/hospital-pathway;AUTO_SERVER=TRUE" `
     -user sa -password "" -sql "SELECT COUNT(*) FROM referral"
```

Two habits keep the evidence honest: do not run Maven twice at the same time in `java/` (a second
build breaks `clean`), and stop the application before running a file-mode test - `AUTO_SERVER` lets
a test JVM attach to the live database file otherwise.

## What was verified

* `.\mvnw.cmd -B clean test` is green with a Camunda 8 cluster running: **26 tests** - 7 model/worker
  coverage tests, 7 database tests, 6 worker-persistence tests, 2 corridor-message tests and 4
  end-to-end tests that start a real instance and drive it through the pathway on the cluster.
  Without a cluster, `.\mvnw.cmd -B "-Dtest=!HospitalPathwayProcessTest" test` runs the 22 that need
  none. The process tests show the workers completing real jobs; e.g.

  ```
  ReferralWorkers    : referral REF-MISSING-...: asking the referring organisation for 'referral letter' (job 2251799813685416)
  ExternalPartyMessenger : external party answered 'Requested referral documents' (correlationKey=REF-MISSING-...)
  ReportingWorkers   : monitoring run: collected the pathway and exception data set DS-2251799813685385 (job 2251799813685385)
  ReportingWorkers   : monitoring run: published reports RPT-REFERRALS; ... with role-based access (job 2251799813685453)
  IdentityAccessWorkers : identity STAFF-1: access decision audited as AUD-2251799813685437 (job 2251799813685437)
  ```

* `mvn spring-boot:run "-Dspring-boot.run.arguments=--hospital.simulation.scenario=monitoring"` with
  the model deployed: the 50 job workers register, the scenario starts an instance and the app's own
  workers complete the reporting path. The excerpt was captured against the previous revision of the
  model - the monitoring entry now sits on the inbox catch event `Inbox_P15_Start_Monitor`:

  ```
  process instance 2251799813687141  COMPLETED  incident=false
  element instances: P15_Start_Monitor, P15_Task_Collect, P15_Task_Generate, P15_Task_Publish, P15_End_Report  COMPLETED
  ```

* The H2 system of record (see [The H2 system of record](#the-h2-system-of-record)): a run leaves
  `data/hospital-pathway.mv.db` holding the 17 tables and the seeded demo rows, a value a worker wrote
  survives a restart, and `data/` is the only thing to delete to start the demo from the seed again.
  The evidence is in `../_analysis/VERIFICATION-H2-WORKERS.md`.

* The Maven wrapper (see [Build with the wrapper](#build-with-the-wrapper)):
  `./mvnw dependency:resolve` resolves every declared dependency including test scope, and
  `.\mvnw.cmd -o "-Dtest=BpmnModelCoverageTest" test` is 7/7 green in about 3.5 s with neither a
  cluster nor a network connection.

## Notes

* User tasks are not implemented here - they belong to Tasklist, and the deployment-bound forms in
  the bundle folder are used unchanged.
* The model had defects that prevented any deployment; the repairs (job types, gateway condition,
  message subscription, `bpmn:group`) are documented in [`../README.md`](../README.md) and can be
  replayed with `../_analysis/repair_model.py`.
* If the `mvn` on your `PATH` is a broken shim (one installed by an IDE extension, for example) it can
  resolve its own home to an MSYS path and fail with `ClassNotFoundException:
  org.codehaus.plexus.classworlds.launcher.Launcher`. The wrapper above is the fix - `./mvnw` /
  `.\mvnw.cmd` never touch that shim, they run the Maven they unpacked into `~/.m2/wrapper/dists`
  (Maven's own per-user cache). `../_analysis/mvnx.sh` is an older workaround for such a `mvn`: it
  calls the launcher directly with a native classpath against the Maven home in `MVN_HOME`
  (`MVN_HOME=/path/to/apache-maven-3.9.x bash ../_analysis/mvnx.sh ...`), and is kept as a fallback
  only.
* IntelliJ IDEA does not pick the wrapper up on its own: set `Settings -> Build, Execution,
  Deployment -> Build Tools -> Maven -> Maven home` to *Wrapper*. The IDE setting lives in `.idea/`,
  which is machine-specific.

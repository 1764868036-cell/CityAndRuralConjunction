# Business Process Test Plan - English execution strategy

Referenced by [`deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx`](deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx).
Results are registered in [`TEST_RESULTS.md`](TEST_RESULTS.md); the plan that produced the work is
[`PROJECT_PLAN.md`](PROJECT_PLAN.md).

---

## 1. Purpose

State how the executable model `Process_Hospital_Integrated` is tested, which level each check
belongs to, and where its evidence lives. The acceptance plan supplies the *cases*; this document
supplies the *strategy* and the *evidence index*.

---

## 2. Test levels

| Level | What is checked | Tooling | Where the evidence lands |
| --- | --- | --- | --- |
| L1 Static model integrity | every automated step names a job type, every job type has a worker, every worker maps to a model step, correlation keys match the model, referenced forms exist, form ids are unique | JUnit `BpmnModelCoverageTest` | `evidence/tests/` |
| L2 Persistence | schema creation, repository round-trips, idempotent seed replay, foreign-key rejection, audit and event-log subjects | JUnit `HospitalDatabaseTest`, `WorkerPersistenceTest` | `evidence/tests/` |
| L3 Message corridor | a message survives an unknown referral; a failing audit trail does not block the corridor message | JUnit `CorridorMessageAuditTest` | `evidence/tests/` |
| L4 Process execution | complete user tasks and publish messages on a real engine, then assert the resulting active element, variables and terminal state | JUnit `HospitalPathwayProcessTest` (Camunda Process Test) | `evidence/tests/`, `evidence/runtime/` |
| L5 Design-time rule evaluation | FEEL gateway conditions evaluated against recorded variable sets | FEEL harness | `evidence/validation/feel-*.json` |
| L6 Manual walkthrough | the whole pathway walked by hand: which step waits for which message, what to enter in Tasklist, which variables a start message must carry | `MANUAL_TEST_GUIDE.md` | operator notes, instance traces |

**Level discipline.** L5 never substitutes for L4. A FEEL expression that returns the expected
value proves the rule logic, not that a token reached the gateway. Cases that only have L5 evidence
are reported as *planned*, never as passed.

---

## 3. How to run

```bash
# 1. cluster (its own folder, wherever c8run was unpacked)
./c8run start                       # ports 26500 / 8080 / 9600

# 2. deploy model and forms in ONE request - the forms are deployment-bound
cd HospitalPatientPathway
curl -X POST http://localhost:8080/v2/deployments \
  $(for f in 1-17.bpmn *.form; do printf ' -F resources=@%s' "$f"; done)

# 3. automated suite (L1-L4). Maven wrapper is bundled; no machine-wide Maven needed.
cd java && ./mvnw test

# 4. workers, for a manual walkthrough (L6)
cd java && ./mvnw spring-boot:run
```

Windows PowerShell: use `curl.exe` (not the `Invoke-WebRequest` alias) and `.\mvnw.cmd`.
`java/application-example.properties` is the configuration template for step 4; copy it to
`src/main/resources/application.properties` and adjust the simulation switches.

---

## 4. Evidence index

| Folder | Contents | Reading rule |
| --- | --- | --- |
| `evidence/tests/` | five Surefire text summaries, five method-level XML reports, four Maven logs | the clean run is 28/28; an earlier "without cluster" log records a historical connection error, not a current failure |
| `evidence/validation/` | Modeler and engine validation, form mapping audit, form-js browser check, FEEL results plus a SHA-256 manifest | distinguish findings inside the executable hospital scope from warnings about the non-executable external pools |
| `evidence/runtime/` | one deployment response and 13 groups of `*-status.json`, `*-trace.json`, `*.jsonl` | read status, event log and element trace together; a `COMPLETED` instance alone does not prove every path |

---

## 5. Status vocabulary

| Status | Meaning |
| --- | --- |
| Executed / reproducible | proven by a file here and reproducible from the current source |
| Sampled | a runtime trace exists for one or more instances, captured against an earlier deployment |
| Planned | no runtime claim; design-time FEEL results are labelled separately |
| Pass | the measurable outcome in the acceptance case matched, with no incident |
| Fail | any count, route, variable or terminal state differed, or an incident was raised |

---

## 6. Traceability

Requirement → business rule → acceptance case → evidence:

- Requirement and rule catalogue: acceptance plan, R-01 … R-13 mapped to BR-01 … BR-11.
- Case to requirement: acceptance plan, AT-01 … AT-16.
- Case to evidence: [`TEST_RESULTS.md`](TEST_RESULTS.md) §3.
- Worker to model element: `java/WORKER_MAP.md`.
- Task to form: `Form_Task_Mapping.csv` (81 rows, one per user task).

---

## 7. Known limits of this test plan

1. External parties are simulated. No test exercises a real insurer, payment provider or
   scheduling service.
2. Timing-dependent routes (reminder intervals, the 7-day letter wait, the treatment retry delay)
   are verified at design time and by sampled traces only; no accelerated-clock test exists.
3. One `COMPLETED` instance per sampled route does not prove the route for all data combinations.
4. Clinical urgency, suitability and funding thresholds are not specified by the case study; the
   tests assert the recorded value, not a clinically correct one.

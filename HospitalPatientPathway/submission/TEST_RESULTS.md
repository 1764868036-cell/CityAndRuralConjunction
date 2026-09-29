# Test results and evidence register

Result date: 29 September 2026. Tests use synthetic data. The full local acceptance decision is **prototype demonstrated with open negative, boundary and timer cases**, as detailed in `ACCEPTANCE_TEST_PLAN.md`.

## 1. Current repeatable test run

Command from `java/` on 29 September 2026: `./mvnw.cmd -q clean test`, Java 21, with local Camunda 8.9.19 available on REST port 8080 and gRPC port 26500. The command exited **0**. The five current test classes ran **28 tests, 0 failures, 0 errors, 0 skipped**.

| Test class | Tests | Result | Evidence |
| --- | ---: | --- | --- |
| `BpmnModelCoverageTest` | 7 | Pass | `evidence/tests/BpmnModelCoverageTest.txt` |
| `HospitalPathwayProcessTest` | 6 | Pass | `evidence/tests/HospitalPathwayProcessTest.txt` and `TEST-io.camunda.demo.hospital.HospitalPathwayProcessTest.xml` |
| `CorridorMessageAuditTest` | 2 | Pass | `evidence/tests/CorridorMessageAuditTest.txt` |
| `HospitalDatabaseTest` | 7 | Pass | `evidence/tests/HospitalDatabaseTest.txt` |
| `WorkerPersistenceTest` | 6 | Pass | `evidence/tests/WorkerPersistenceTest.txt` |

The current full console record is `evidence/tests/maven-evidence-closure-2026-09-29.log`. Its five Surefire XML reports identify individual test methods. The targeted six-case integration run is `evidence/tests/negative-branch-integration-2026-09-29.log`. The earlier 26-test successful baseline is preserved as `maven-clean-test-2026-09-29.log`. Before the cluster was started, an earlier attempt produced 22 passing tests and one environment error because `HospitalPathwayProcessTest` could not connect to `127.0.0.1:8080`; its record is `maven-without-cluster-2026-09-29.log`. The subsequent clean run removed stale reports. A removed historical `StandaloneWorkerIsolationTest` report is excluded from the current result.

The model coverage test also passed on the initial public repository checkout (7/7); its Surefire summary is `evidence/tests/BpmnModelCoverageTest.txt`. The current full suite was run against the same curated project files before publishing revision r2.

## 2. Model, form and deployment checks

| Check | Result | Evidence and interpretation |
| --- | --- | --- |
| Camunda Modeler 8.9 lint | 0 parse warnings, 0 errors, 0 warnings | `evidence/validation/modeler-runtime-final.json` |
| Camunda engine schema and executable deployment scope | Schema valid; 0 deployment-scope errors/warnings | `evidence/validation/engine-runtime-final.json`. Its 31 findings for non-executable external participant pools are separately scoped, not hospital deployment errors. |
| User-task form mapping | 81 collaboration user tasks mapped: 70 hospital, 11 external | `Form_Task_Mapping.csv`, `evidence/validation/form-mapping-audit.json` |
| Form-js render and sample submission | 81/81 task views passed with local sample data | `evidence/validation/form-browser-check.json`. This is a browser form check, not 81 live Tasklist submissions. |
| Local model plus forms deployment | One hospital definition plus 16 form resources in one deployment | `evidence/runtime/deployment-v4-black-style.json` |
| Worker contract | 50 job types for 51 executable automated steps | `java/WORKER_MAP.md`, `evidence/tests/BpmnModelCoverageTest.txt` |
| Business-rule expression evaluation | 1386 BPMN expressions parsed; 53/53 mapped FEEL cases passed | `evidence/validation/feel-2026-09-29.json`, `feel-cases-2026-09-29.json`, `feel-run-manifest.json`. The manifest records the BPMN and result SHA-256. This is design-time evaluation, not process-token execution. |

## 3. Camunda runtime evidence

The 13 copied `*-status.json` records below all state `COMPLETED`, `hasIncident=false`, and have empty tasks, active elements and incidents. Each instance has a matching `*.jsonl` event log and `*-trace.json` element history in `evidence/runtime/`. Those files show the task submissions and external messages used by the local driver; external parties were simulated.

| Scenario / scope | Instance ID | Recorded outcome |
| --- | --- | --- |
| Earlier full route, including first Tasklist form interaction | `2251799813787085` | Completed |
| Full route on semantic model v3 | `2251799813792570` | Completed |
| Full route on visual v4 | `2251799813799469` | Completed; 34 user task submissions, 5 external messages, 139 completed element records in its logs/trace |
| Formal treatment change not accepted | `2251799813803472` | Completed |
| Approved treatment change with cost impact and refund route | `2251799813803956` | Completed |
| Appointment cancellation then rebooking | `2251799813805423` | Completed |
| Another full clinical, finance, letter and follow-up route | `2251799813806427` | Completed |
| Finance-category enquiry | `2251799813808280` | Completed |
| Identity and access route | `2251799813808708` | Completed |
| System interruption and restoration | `2251799813809073` | Completed |
| Appointment outcome with clinical review | `2251799813810277` | Completed |
| No-show and referring-party notification | `2251799813811466` | Completed |
| Fund transfer to another appointment | `2251799813811822` | Completed |

The status alone proves only final state and lack of active incident. The event log and trace are needed to confirm a specific route. The full route used a serial task-completion policy for active correspondence and follow-up branches. It did not accelerate the 7-day, 23-day or two-month correspondence timers or test two concurrent independent letters.

## 4. Acceptance plan status

| Case | Status supported by current evidence |
| --- | --- |
| AT-01 | Pass for local deploy/model/form/worker integrity. |
| AT-02 | Pass for one complete, suitable synthetic referral route. |
| AT-03 | Pass in current 6-case integration suite. |
| AT-04 | Pass in current 6-case integration suite for the declined-referral route. |
| AT-05 | Positive slot route sampled; no-slot rules evaluated with FEEL, but token-level boundary/escalation cases planned. |
| AT-06 | Consent-true route sampled; refusal case planned. |
| AT-07 | Positive service/funding sampled; denied cases planned. |
| AT-08 | Paid route sampled; duplicate-charge boundary case planned. |
| AT-09 | Stop/refund sampled; contradictory stop/change FEEL case passed, while token-level contradiction and continue cases remain planned. |
| AT-10 | Sampled approved and not-accepted change routes passed. |
| AT-11 | Unverified identity negative case passed in Camunda integration test; three date/contact runtime cases planned. FEEL boundary cases passed at design time. |
| AT-12 | Three sampled cancellation/review/no-show outcomes completed. |
| AT-13 | Sampled refund and transfer outcomes completed; unauthorised refund rule passed in FEEL, while its Camunda negative case remains planned. |
| AT-14 | Finance enquiry and completed letter sampled; suspected-error FEEL rule passed, while its token route and full timer cases remain planned. |
| AT-15 | Approved access and downtime restoration sampled; denied-access negative case passed in Camunda integration test. |
| AT-16 | Structural form/worker checks passed; 81/81 local form sample submissions passed. |

This status table is deliberately narrower than a claim that all acceptance criteria passed. The additional work and exact evidence needed are recorded in `EVIDENCE_CLOSURE_RECORD.md`. Pending negative, boundary and time-dependent cases should be executed before production acceptance.

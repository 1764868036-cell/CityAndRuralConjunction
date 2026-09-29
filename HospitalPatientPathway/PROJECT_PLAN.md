# Hospital Patient Pathway - Project Plan

Submission 2026-09-29. This document is the **as-built** project plan: product backlog, sprint
backlogs, planning evidence and the revision log that reconciles the plan with the test results
actually recorded in [`evidence/`](evidence/README.md).

> **Provenance note.** The project did not keep sprint records while it was being built. The sprint
> split below is reconstructed from the deliverables, the 18 work rounds recorded in
> `WORK_REPORT.md`, and the timestamps of the evidence files. Where a date is inferred rather than
> recorded, the row is marked *inferred*. The **Revision log** at the end is not inferred: every
> entry cites a named artefact that exists in this repository.

---

## 1. Scope, objectives and out-of-scope

| ID | Objective | Linked requirement | Status |
| --- | --- | --- | --- |
| O1 | Model all 17 case-study processes as one executable Camunda 8 collaboration | R-01 | Done |
| O2 | Make participants, responsibilities and system boundaries explicit | R-01 | Done |
| O3 | Encode the business rules as measurable, testable conditions | BR-01 … BR-11 | Done |
| O4 | Automate every automated step with external job workers | R-13 | Done (50 job types) |
| O5 | Give every human task a bound Camunda Form | R-13 | Done (81/81) |
| O6 | Prove the result with a repeatable suite and a traceable acceptance plan | R-01 … R-13 | Partial - see §5 |

**Out of scope (recorded assumptions).** No real HIS/PAS, payment gateway or insurer integration;
external parties are simulated. No clinical urgency, suitability or funding threshold is invented -
the case study does not quantify them, so the forms record the assessment and the gateway reads the
recorded value. The system of record is an embedded H2 database, not a production store.

---

## 2. Product backlog

Epics map one-to-one onto the requirement catalogue in
[`deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx`](deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx).

| ID | Epic / user story | Acceptance case | Pri | Est | Status |
| --- | --- | --- | --- | --- | --- |
| PB-01 | As a referring organisation, I can submit a referral package that the hospital registers and checks for completeness | R-02 / AT-02, AT-03 | High | 8 | Done |
| PB-02 | As a consultant, I can review a referral and record suitability and priority | R-02 / AT-04 | High | 8 | Done |
| PB-03 | As a bookings team member, I can offer a consultation slot and capture the patient's answer | R-03 / AT-05 | High | 13 | Partial |
| PB-04 | As a consultant, I can record diagnosis, explain risks and capture informed consent | R-04 / AT-06 | High | 8 | Partial |
| PB-05 | As a care coordinator, I can arrange treatment and external services and react to unavailability | R-05 / AT-07 | High | 13 | Partial |
| PB-06 | As finance, I can classify the payer, record the allocation and resolve a funding gap | R-05 / AT-07 | High | 8 | Partial |
| PB-07 | As finance, I can record a payment result and guard against a duplicate charge | R-06 / AT-08 | High | 8 | Partial |
| PB-08 | As a clinician, I can continue, change or stop a treatment cycle | R-07, R-08 / AT-09, AT-10 | High | 13 | Partial |
| PB-09 | As a bookings clerk, I can handle cancellations, clinical review and no-shows | R-03 / AT-12 | Med | 8 | Done |
| PB-10 | As finance, I can process an authorised refund or a fund transfer | R-10 / AT-13 | Med | 8 | Partial |
| PB-11 | As an enquiry handler, I can classify an enquiry and control clinic letter release | R-11 / AT-14 | Med | 13 | Partial |
| PB-12 | As a member of staff, I can request access and keep working through a system interruption | R-12 / AT-15 | Med | 8 | Partial |
| PB-13 | As a coordinator, I can change an appointment after verifying identity | R-09 / AT-11 | Med | 13 | Partial |
| PB-14 | As a reporting team, I can collect, generate and publish pathway reports (process 15) | R-01 | Low | 5 | Done (service tasks, no form) |
| PB-15 | Deploy the model and its forms as one bundle and keep deployment reproducible | R-01 / AT-01 | High | 5 | Done |
| PB-16 | Every automated step is backed by a job worker, every human task by a form | R-13 / AT-16 | High | 13 | Done |
| PB-17 | The pathway is demonstrable end to end without hand-publishing most messages | R-01 | Med | 8 | Done (manual gates documented) |
| PB-18 | Automated test suite covers model, persistence, workers and integration paths | R-13 | High | 13 | Done (28/28) |
| PB-19 | Acceptance plan with traceable requirements, cases and evidence | R-01 … R-13 | High | 13 | Done |
| PB-20 | Presentation deck covering background, objectives, solution, demo and results | - | Med | 8 | Done |

**Definition of done.** Model deploys clean; the story's automated steps have a worker; its human
tasks have a bound form; at least one automated test or a recorded runtime trace covers the story;
the acceptance case states precondition, data, action, expected outcome and pass/fail.

---

## 3. Sprint backlogs

Sprint length: one working session (the assessment ran as a compressed delivery). Dates are
*inferred* from artefact timestamps except where noted.

### Sprint 1 - Model repair and executable baseline *(inferred)*
**Goal:** make the supplied model deploy at all.
- Repair the 20 model defects that blocked deployment (see `README.md` → Model repairs).
- Establish one executable process with external black-box pools.
- **Delivered:** `1-17.bpmn` (deployable), `README.md` repair notes.
- **Evidence:** `evidence/validation/modeler-runtime-final.json`, `engine-runtime-final.json`.

### Sprint 2 - Worker backbone *(inferred)*
**Goal:** every automated step has a worker; the cluster can drive the model.
- Spring Boot worker application under `java/`, 13 worker classes, 50 job types.
- Simulation layer answering for the external parties.
- **Delivered:** `java/` (source, `pom.xml`, Maven wrapper, `application-example.properties`).
- **Evidence:** `BpmnModelCoverageTest` 7/7 (every job type has a worker, every worker maps to a step).

### Sprint 3 - Forms and task bindings *(inferred)*
**Goal:** every human task renders a form and submits into the right variables.
- 16 deployment-bound forms; remove the group `path` nesting that swallowed submitted values.
- `Form_Task_Mapping.csv` extended to all 81 user tasks.
- **Delivered:** `*.form`, `Form_Task_Mapping.csv`.
- **Evidence:** `evidence/validation/form-mapping-audit.json`, `form-browser-check.json`.

### Sprint 4 - Persistence and message corridor *(inferred)*
**Goal:** the pathway leaves a durable record and external answers correlate reliably.
- Embedded H2 system of record (20 tables), repositories, audit and event log.
- Correlation keys aligned with the model; auto-responder publishes with the waiting instance's key.
- **Delivered:** `java/src/main/java/.../persistence`, `simulation/`.
- **Evidence:** `HospitalDatabaseTest` 7/7, `WorkerPersistenceTest` 6/6, `CorridorMessageAuditTest` 2/2.

### Sprint 5 - Integration paths and negative branches *(inferred)*
**Goal:** prove the main route and the denial routes on a real engine.
- Full synthetic run; referral, documents, access and monitoring paths; then the two negative
  branches added late in the sprint.
- **Delivered:** `HospitalPathwayProcessTest` (6 scenarios), runtime traces for 13 instance groups.
- **Evidence:** `evidence/tests/*.xml` and `*.txt`, `evidence/runtime/*-status.json` + `-trace.json` + `.jsonl`.

### Sprint 6 - Evidence closure and submission *(recorded: 2026-09-29)*
**Goal:** make every claim in the acceptance plan traceable to a file in the repository.
- Copy Surefire reports and Maven logs into `evidence/tests/`.
- FEEL design-time evaluation (53/53) with a SHA-256 manifest in `evidence/validation/`.
- Acceptance plan revised to distinguish *executed* from *planned*, with an evidence index.
- Clean run of the whole suite recorded: **28/28**.
- **Delivered:** `evidence/`, `deliverables/`, `TEST_RESULTS.md`,
  `BUSINESS_PROCESS_TEST_PLAN_EN.md`, `presentation/`.
- **Evidence:** `evidence/README.md`, `evidence/tests/maven-clean-test-2026-09-29.log`.

---

## 4. Planning evidence

| Evidence | Where |
| --- | --- |
| Change log of 18 work rounds (what changed, why, how verified) | `WORK_REPORT.md` |
| Manual walkthrough used for planning the demo path | `MANUAL_TEST_GUIDE.md` |
| Worker-to-element index used to size the worker backlog | `java/WORKER_MAP.md` |
| Job-type, message and simulation design | `java/README.md`, `java/RUNTIME_COMPATIBILITY.md` |
| Test execution records | `evidence/tests/`, `TEST_RESULTS.md` |
| Design-time rule evaluation | `evidence/validation/feel-2026-09-29.json` |
| Runtime instance traces | `evidence/runtime/` |
| Automated suite result in the deck | `presentation/Hospital_Patient_Pathway_Presentation.pptx` (slides 22-26) |

---

## 5. Revision log - plan adjusted against the recorded test results

This is the part of the plan that changed **because of what the tests actually showed**.
Every reason cites a concrete artefact.

| # | Affected item | Plan before | Plan after | Reason (test evidence) |
| --- | --- | --- | --- | --- |
| R1 | PB-18, integration scope | 4 integration scenarios: referral outcome sent, missing documents correlated back, access request, monitoring run | **6** scenarios; added *denied access is audited without granting access* and *unverified appointment change cannot update the booking* | `evidence/tests/TEST-io.camunda.demo.hospital.HospitalPathwayProcessTest.xml` reports `tests="6"` against the current `HospitalPathwayProcessTest.java`, which contains both added methods and passes. The two added scenarios are exactly the negative routes that AT-11 and AT-15 previously listed as *planned*. |
| R2 | Test totals quoted everywhere | 24 / 24 | **28 / 28** | Five Surefire XMLs in `evidence/tests/` sum to 7+6+7+6+2 = 28 with `failures="0" errors="0"`. `evidence/README.md` states the clean run passed 28/28. |
| R3 | PB-03 … PB-13 (partial stories) | Negative and boundary routes planned to be closed inside the delivery | Split into *design-time FEEL pass (53/53)* and *runtime token route outstanding*, moved to the post-acceptance backlog | `evidence/validation/feel-2026-09-29.json` evaluates the rules at design time, but `evidence/runtime/` holds no instance trace for e.g. the no-slot escalation, duplicate-charge guard or suspected-error letter. A FEEL result is not a token execution, so the plan must not claim those as passed. |
| R4 | AT-11 status | Planned (no runtime evidence) | Partially executed: identity-not-verified route is covered; the three verified-date / 14-day contact token routes stay planned | `HospitalPathwayProcessTest.unverifiedAppointmentChangeCannotUpdateBooking` passes and returns to correction without updating the booking (`evidence/tests/TEST-…HospitalPathwayProcessTest.xml`). Only one of the four data cases in AT-11 is exercised. |
| R5 | AT-15 status | Planned | Partially executed: approved access and downtime recovery have runtime traces; the denial route is now covered by an automated test | Runtime samples `2251799813808708` / `2251799813809073` in `evidence/runtime/`; denial covered by `deniedAccessIsAuditedWithoutGrant`. |
| R6 | AT-12, AT-13, AT-14 | Sampled at runtime, treated as closed | Kept as *executed (sampled)* with the explicit caveat that one `COMPLETED` instance does not prove every path | `evidence/README.md` instructs readers to inspect status, event log and trace together. The plan now repeats that caveat instead of implying full path coverage. |
| R7 | PB-03 form/task index | Index covered 68 user tasks against 15 forms | Index covers **81 user tasks against 16 forms** (`Form_Task_Mapping.csv`, 81 data rows) | Model audit: 81 `userTask` elements, 16 distinct `formId` references, 0 orphans and 0 missing. Process 17 was added after the first index was written, which left 13 tasks undocumented. |
| R8 | PB-15 deployment evidence | `dep.json` retained as the deployment record | **Removed** from the deliverable | `dep.json` records `processDefinitionId: Process_Hospital_Merged`; the current model is `Process_Hospital_Integrated`. The file describes a superseded deployment and would contradict AT-01 if quoted. |
| R9 | PB-17 demo path | Full auto-walkthrough, no manual message | Auto-answer retained, but two gates documented as manual: the incoming referral package and the clinic-visit completion | `MANUAL_TEST_GUIDE.md` records which steps wait for which message; the auto-responder covers 4 messages only. Planning the demo as fully automatic produced stalled instances during Sprint 5. |
| R10 | Deliverables list | No project plan, no test-result register, no English test plan | Added `PROJECT_PLAN.md`, `TEST_RESULTS.md`, `BUSINESS_PROCESS_TEST_PLAN_EN.md` | The acceptance plan text references `TEST_RESULTS.md` and `BUSINESS_PROCESS_TEST_PLAN_EN.md` as its result register and execution strategy; before this change both references dangled. |
| R11 | PB-20 presentation | Deck covered the 17 processes and a test summary only | Added background and objectives, technical solution and architecture, demo flow and acceptance overview; corrected the integration figure from 4/4 to 6/6 and the total to 28/28 | Same evidence as R1/R2. The old figures were captured before the two negative-branch tests were added and would have contradicted `evidence/tests/`. |
| R12 | Source-of-truth discipline | Not planned | Added as a backlog item: the repository is the single source of truth; compare the working copy against it before any submission | A working copy outside the repository was still at 26/26 and missing the two added tests, which produced a wrong readiness conclusion. The repository copy is the one that matches `evidence/`. |

---

## 6. Open items carried after this plan

| Item | Why it is still open | Next step |
| --- | --- | --- |
| Runtime traces for the negative/boundary acceptance cases | Only FEEL design-time results exist; no instance trace | Re-run the cluster, drive the remaining cases, add `<key>-status.json` + `-trace.json` per case |
| `dep.json` replacement | Removed as stale | Capture a fresh `POST /v2/deployments` response as `evidence/runtime/deployment-<revision>.json` |
| Clinical urgency / funding thresholds | Deliberately not invented | Confirm with the stakeholder before any production reading of BR-02, BR-04 |
| Working-copy clean-up | Build output, logs and analysis scripts exist outside the repository | Keep `.gitignore` (already excludes `java/target/`, `java/data/`, `_analysis/`, `*.mv.db`) and do not commit them |

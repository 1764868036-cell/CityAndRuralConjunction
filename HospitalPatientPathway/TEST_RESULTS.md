# Test Results

Result register for the Hospital Patient Pathway submission, 2026-09-29.
Referenced by [`deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx`](deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx)
and by [`PROJECT_PLAN.md`](PROJECT_PLAN.md).

**Reading rule used throughout this document.**

- **Executed / reproducible** - a file in this repository proves the result, and the result can be
  reproduced today by running the current source.
- **Sampled** - a runtime trace exists for one or more instances, but the trace is a point-in-time
  capture against an earlier deployment; it is evidence that a route ran, not a regression test.
- **Planned** - no runtime claim is made. Design-time FEEL evaluation is **not** Camunda token
  execution and is always labelled separately.

---

## 1. Automated suite - 28 / 28 pass

Source: `evidence/tests/*.xml` and `*.txt`, plus the Maven logs in the same folder.
Command: `cd java && ./mvnw test` (Maven wrapper, no machine-wide Maven needed).

| Suite | Tests | Pass | Fail | Error | What it proves |
| --- | --- | ---: | ---: | ---: | --- |
| `BpmnModelCoverageTest` | 7 | 7 | 0 | 0 | every automated step names a job type; every job type has a worker; every worker maps to a step; every waited-for message can be published; correlation keys match the model; every referenced form exists; form ids are unique |
| `HospitalPathwayProcessTest` | 6 | 6 | 0 | 0 | real engine run: monitoring, access request, denied access, unverified appointment change, missing documents correlated back, declined referral |
| `HospitalDatabaseTest` | 7 | 7 | 0 | 0 | schema, repository round-trips, idempotent seed replay, foreign keys, audit and event-log subjects |
| `WorkerPersistenceTest` | 6 | 6 | 0 | 0 | worker writes reach the store; repeat instances update rather than duplicate; no worker bypasses the persistence layer |
| `CorridorMessageAuditTest` | 2 | 2 | 0 | 0 | message publication survives an unknown referral; a failing audit trail does not stop the corridor message |
| **Total** | **28** | **28** | **0** | **0** | clean run of the current source |

### Integration scenarios (the six)

| # | Method | Route exercised | Acceptance case |
| --- | --- | --- | --- |
| 1 | `monitoringRunIsCarriedOutByTheWorkers` | process 15 monitoring request reaches the reporting workers | R-01 |
| 2 | `missingDocumentsAreRequestedAndCorrelatedBack` | incomplete referral → request → return message correlates to the same instance | AT-03 |
| 3 | `accessRequestRunsThroughHumanSteps` | access request follows the human and audit steps | AT-15 (approved) |
| 4 | `declinedReferralEndsAfterSendingTheOutcome` | unsuitable referral takes the decline route and sends the outcome | AT-04 |
| 5 | `deniedAccessIsAuditedWithoutGrant` | denied access is audited without entering the protected action | AT-15 (denial) |
| 6 | `unverifiedAppointmentChangeCannotUpdateBooking` | unverified identity returns to correction and does not update the booking | AT-11 (identity) |

Scenarios 5 and 6 were added after the first evidence capture. Any document or slide quoting
**4 / 4** or **24 / 24** is stale; the current figures are **6 / 6** and **28 / 28**.

---

## 2. Design-time rule evaluation - 53 / 53

Source: `evidence/validation/feel-2026-09-29.json`, `feel-cases-2026-09-29.json`,
`feel-run-manifest.json` (SHA-256 of the evaluated model).

53 FEEL expressions taken from the tagged `1-17.bpmn` were evaluated against recorded variable
sets. This proves the rule *logic* for the gateway conditions; it does **not** prove that a token
reached the gateway. Treat every row below as design-time only.

---

## 3. Acceptance cases - status

Status and evidence per case, exactly as recorded in the acceptance plan.

| Case | Requirement | Status | Evidence in this repository |
| --- | --- | --- | --- |
| AT-01 Deployment and resource integrity | R-01, R-13 | Executed / reproducible | `evidence/tests/BpmnModelCoverageTest.txt`, `evidence/validation/*` |
| AT-02 Complete suitable referral reaches the main pathway | R-02 / BR-01 | Sampled | `evidence/runtime/2251799813799469-*` |
| AT-03 Missing referral documents | R-02 / BR-01 | Executed / reproducible | `HospitalPathwayProcessTest.missingDocumentsAreRequestedAndCorrelatedBack` |
| AT-04 Unsuitable referral | R-02 / BR-01 | Executed / reproducible | `HospitalPathwayProcessTest.declinedReferralEndsAfterSendingTheOutcome` |
| AT-05 Consultation availability and escalation | R-03 / BR-02 | Partly planned | available-slot route in AT-02 trace; no-slot and escalation routes have FEEL only |
| AT-06 Consent refusal blocks treatment | R-04 / BR-03 | Planned | consent-true route only (AT-02) |
| AT-07 Unavailable service or declined funding | R-05 / BR-04 | Planned | positive funding route only (AT-02) |
| AT-08 Paid result and prior-charge guard | R-06 / BR-05 | Partly planned | positive paid path in AT-02; duplicate-charge case has FEEL only |
| AT-09 Continuation versus clinical stop | R-07 / BR-06 | Partly planned | stop/refund sampled; continuation and contradictory token routes planned |
| AT-10 Formal treatment change and cost recheck | R-08 / BR-07 | Sampled | `evidence/runtime/2251799813803956-*`, `2251799813803472-*` |
| AT-11 Appointment change identity, date, contact | R-09 / BR-08 | Partly executed | identity-not-verified covered by `unverifiedAppointmentChangeCannotUpdateBooking`; the three verified-date / 14-day cases are FEEL only |
| AT-12 Cancellation, clinical review, no-show | R-03 | Sampled | `evidence/runtime/2251799813805423-*`, `2251799813810277-*`, `2251799813811466-*` |
| AT-13 Authorised refund versus transfer | R-10 / BR-09 | Sampled | `evidence/runtime/2251799813803956-*`, `2251799813811822-*`; unauthorised-refund case FEEL only |
| AT-14 Enquiry classification and letter controls | R-11 / BR-10 | Partly planned | finance enquiry and approved letter sampled (`2251799813808280`, `2251799813799469`); suspected-error route and full-duration timers planned |
| AT-15 Access and downtime recovery | R-12 / BR-11 | Executed / reproducible | `accessRequestRunsThroughHumanSteps` and `deniedAccessIsAuditedWithoutGrant`; downtime sampled at `2251799813808708`, `2251799813809073` |
| AT-16 Form rendering and worker contract | R-13 | Executed / reproducible | `BpmnModelCoverageTest` (7/7), `evidence/validation/form-mapping-audit.json`, `form-browser-check.json` |

**Count.** 4 cases executed and reproducible, 2 partly executed, 5 sampled, 5 partly planned or
planned. The acceptance plan's closing statement still applies: the evidence supports a *local
prototype demonstration*, not unconditional production acceptance.

---

## 4. Environment for the recorded results

| Component | Value |
| --- | --- |
| Cluster | local Camunda 8.9 (c8run), ports 26500 / 8080 / 9600 |
| Workers | Spring Boot 4.0.5, `camunda-spring-boot-starter` 8.9.0, Java 21 |
| Store | embedded H2 file database under `java/data/` (excluded from the repository) |
| Data | synthetic identifiers only (`REF-*`, `PAT-*`, `INC-*`, `BKG-*`, `APPT-*`) |
| Model | `1-17.bpmn`, executable process `Process_Hospital_Integrated` |

The cluster is not running while this register is written, so no new runtime trace was captured in
this session; every runtime artefact listed above was produced earlier and is stored verbatim.

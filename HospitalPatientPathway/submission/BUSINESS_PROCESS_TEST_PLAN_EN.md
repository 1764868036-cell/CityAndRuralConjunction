# Hospital Patient Pathway Business Process Test Plan

Document version V1.2. Revised 29 September 2026. Submission tag `hospital-pathway-final-2026-09-29-r5`. Applicable model `1-17.bpmn`, SHA-256 `616af009cf00ec674ef7371d48071f8c015b44c1c65f0cf15bd550dc2e9a960b`.

This plan helps the submitter execute and the assessor review tests of the single executable hospital process, its 16 deployed Camunda Forms and its Java external workers. Current evidence demonstrates the main route and selected alternatives in a local prototype. **It does not establish that every acceptance scenario has passed or that the process is safe for live hospital use.** The companion `ACCEPTANCE_TEST_PLAN.md` gives each case's full preconditions, data, actions, expected result and pass/fail rule. This document sets out the execution order, evidence standard and remaining work.

## Scope and test basis

The business scope covers referral registration and missing documents, clinical review, consultation booking, informed consent, service availability and funding, payment, treatment decisions, appointment change or cancellation, refund or transfer, enquiries and letters, access decisions, and interruption recovery. For each path, inspect human tasks, gateway outcomes, message correlation, worker results, stored data and final process state. Denied access, failed identity verification, duplicate charging, timers and concurrent letters have priority because they can produce an incorrect business outcome.

Only synthetic patient data is used. Local simulators answer for external referrers, schedulers, funders and payment services. A completed simulated instance does not prove a live third-party connection, production identity controls or clinical safety. Load, penetration and production data-governance testing require separate work.

The test basis is the editable `1-17.bpmn`, `Form_Task_Mapping.csv`, `java/WORKER_MAP.md`, `submission/ACCEPTANCE_TEST_PLAN.md`, `submission/DESIGN_DECISIONS.md` and `submission/DEPLOYMENT.md`. Requirements R-01 to R-13 and business rules BR-01 to BR-11 in the model determine expected routing. No independent numeric policy was supplied for clinical urgency, suitability or funding, so testers must not invent one.

## Environment people and test data

| Item | Project arrangement |
| --- | --- |
| Runtime | Local self-managed Camunda 8.9.19; REST `http://localhost:8080`, gRPC `http://localhost:26500`; Java 21. Deploy one BPMN and all 16 Forms together as described in `DEPLOYMENT.md`. |
| Workers and external parties | Run the Spring Boot workers in `java/`. Local simulation supplies external replies. Live third-party endpoints, accounts and an integration window have not been supplied. |
| Data | Use unique synthetic `referralId`, `patientId`, payment reference, letter ID and `incidentReference` values. Do not commit real patient records, credentials or the local H2 database. |
| Execution and review | The project submitter runs cases and retains evidence; the assessor may reproduce them from the tagged repository. Named testers, an independent product sign-off and operations owner remain to be supplied by the project team. |
| Tools | Camunda Tasklist and API, Maven Surefire, Camunda FEEL checks, model/form audits, process status and element traces. Postman and Jira are not sources of the current evidence. |

Before a process test, confirm that the cluster ports and workers are available, the BPMN and Forms deploy in one request, and the Git revision and BPMN hash are recorded. Start a new instance for each data combination. Reusing a correlation key across concurrent instances would make message evidence ambiguous.

## Acceptance scenarios and current position

A case passes only when **execution evidence** supports both the required route and its result: expected elements occur or are skipped, business values match the rule, the instance reaches the expected state, and no unexplained incident remains. Where an AT case has several subcases, mark the whole case passed only after every subcase meets its criterion. A positive sample with an untested negative branch is **partial**. A FEEL result checks an expression for supplied values; it does not execute Camunda tasks, messages or timers.

| Case and rule | Input or action | Measurable pass condition | Current judgement and evidence |
| --- | --- | --- | --- |
| AT-01 R-01 R-13 | Deploy BPMN and Forms together. | One hospital definition, 16 Forms and zero deployment errors. | Pass; E1. |
| AT-02 BR-01 | Run a complete, suitable referral. | Reach consultation and complete with no active task or incident. | One sampled route passed; E2. |
| AT-03 BR-01 | Return missing documents using the same `referralId`. | The intended instance resumes review with no wrong correlation. | Integration test passed; E3. |
| AT-04 BR-01 | Set `referralSuitable=false`. | No consultation booking; decline/notification route completes. | Integration test passed; E3. |
| AT-05 BR-02 | Available slot, no-slot retry, urgent or expired request. | Retry stays within `requestedReviewBy`; urgent/expired cases escalate. | Partial: positive E2, FEEL E4; negative token routes pending. |
| AT-06 BR-03 | Set `informedConsent=false`. | Zero treatment-execution elements after refusal. | Partial: consented route E2; refusal pending. |
| AT-07 BR-04 | Unavailable service or `fundingAuthorised=false`. | Zero treatment elements and zero unauthorised payment requests. | Partial: positive funding E2; denied cases pending. |
| AT-08 BR-05 | First payment and existing charge for the same reference. | At most one charge per payment reference. | Partial: paid route E2; duplicate guard pending. |
| AT-09 BR-06 | Continue versus conflicting stop/change decision. | Stop has priority and bypasses change; continue enters the next cycle. | Partial: stop E2, rule E4; token boundaries pending. |
| AT-10 BR-07 | Approved cost-affecting change and unapproved change. | Approved plan rechecks cost/funding; unapproved plan is not implemented. | Two sampled alternatives passed; E5. |
| AT-11 BR-08 | Unverified identity, late date, inside/outside 14 days. | No change without verified identity or with an invalid date; near-term date prompts phone contact. | Partial: identity E3, rules E4; date/contact token routes pending. |
| AT-12 R-03 | Cancellation/rebooking, clinical review and no-show. | Three instances reach distinct expected outcomes without incidents. | Three sampled alternatives passed; E5. |
| AT-13 BR-09 | Authorised refund, transfer and unauthorised refund. | Only authorised route refunds; transfer never invokes refund. | Partial: first two E5, rule E4; unauthorised token route pending. |
| AT-14 BR-10 | Finance enquiry, safe letter, suspected error, reminders, two concurrent letters. | Correct classification; unsafe letter held; timers fire at model intervals without cross-letter leakage. | Partial: enquiry/completed letter E2 E5; timers and concurrency pending. |
| AT-15 BR-11 | Approved/denied access and restoration by `incidentReference`. | Denial grants no permission; restoration correlates only to its instance; no incident remains. | Current sampled subcases passed; E3 E5. |
| AT-16 R-13 | Audit form binding/rendering and worker coverage. | 81/81 task bindings; 50 job types cover 51 automated steps. | Structural checks passed; E1 E3. |

Evidence index: E1 is `evidence/runtime/deployment-v4-black-style.json`, `evidence/validation/form-mapping-audit.json` and `evidence/validation/form-browser-check.json`. E2 is `evidence/runtime/2251799813799469-status.json` with its matching event log and trace. E3 is `evidence/tests/maven-evidence-closure-2026-09-29.log` with five Surefire XML reports and text summaries. E4 is `evidence/validation/feel-run-manifest.json` and its linked 53-case result; it is design-time evidence only. E5 is the branch-instance list in section 3 of `submission/TEST_RESULTS.md`, with matching event logs and traces.

The table gives a review route. `ACCEPTANCE_TEST_PLAN.md` remains the detailed source for each case's preconditions, test data, actions, expected outcome and pass/fail rule. Neither the table nor an instance status alone replaces a case execution record.

## Execution sequence and evidence capture

The recorded local work took place on 28 September 2026 for deployment, model/form audits and 13 synthetic instance samples, and on 29 September for the full Java suite and FEEL checks. The project team must set dates for remaining tests; this plan is not evidence of a past sprint meeting.

1. **Environment check** Record the Git tag, BPMN hash, deployment response and worker startup. Resolve connection failures as environment issues before judging a business case.
2. **Safety-critical branches** Run AT-05 to AT-08, the AT-11 date/contact boundaries and the AT-13 unauthorised refund. Use a distinct instance for each input combination.
3. **Asynchronous paths** Run the AT-09 conflicting decision and AT-14 suspected error, 7-day, 23-day and two-month reminders, and two concurrent letters. Record correlation keys and timer timestamps.
4. **Regression and review** Re-run `./mvnw.cmd -q clean test` from `java/`. Review new case evidence, defect closures and the submission tag; allow the assessor to reproduce key cases.

Each execution record must identify the AT subcase, timestamp, Git revision and BPMN hash, unique instance ID, preconditions and input data, Tasklist values, external message and correlation key, element trace, final state and incident count, expected-versus-actual result, tester and defect ID. Screenshots can support a task submission but cannot replace the process trace. Existing material is in `evidence/tests/`, `evidence/validation/` and `evidence/runtime/`; the result mapping is in `submission/TEST_RESULTS.md`.

## Defects and exit decisions

For wrong routing, correlation, unauthorised action or inconsistent data, retain the inputs, instance ID and actual trace. Re-test the fix with a new instance and regress the neighbouring route. The submission has no independent historical defect-tracker export; do not infer a zero-defect history or invent an approval record.

**Local-demo entry** requires a successful model/form deployment, running workers, prepared synthetic data and a completable main route. **Course-submission exit** requires model, workers, forms, plans, results and evidence under one public Git tag; executed and pending cases clearly separated; the target revision's 28 Java tests passing; and no unexplained blocking incident in archived instances. **Production exit** would additionally require execution of all safety-critical and time-dependent cases, live integration tests, clinical-rule approval, and access/data-governance review. This plan does not claim production exit.

At this revision, the Java suite passed **28/28 with zero failures, errors or skips**; 53 FEEL cases passed; and 13 archived simulated instances show completed states without incidents. Token-level paths in AT-05 to AT-08 and other cases remain unexecuted. The supported overall result is **local prototype demonstrated; full acceptance pending**. See `TEST_RESULTS.md` and `EVIDENCE_CLOSURE_RECORD.md`.

## Risks and inputs still needed

Prioritise event logs and element traces for consent refusal, funding denial, duplicate payment, an invalid appointment date, unauthorised refund and letter timers. If a live third-party environment is unavailable, retain the simulation result and plan real integration testing separately. If the course requires original planning evidence, the team must provide genuine board exports, minutes or task assignments with original dates. `SPRINT_BACKLOGS.md` is a reconstructed delivery view, not a record of a historical ceremony.

## Per-case execution record

Copy this blank record for each pending AT subcase. It is a form for future execution, not a claim that the case has run. Store the referenced logs and screenshots under the relevant evidence folder and link the completed record from `TEST_RESULTS.md`.

| Field | Complete during execution |
| --- | --- |
| Case and tester | AT number, subcase, tester and execution time. |
| Version and environment | Git tag/commit, BPMN SHA-256, deployment version, Camunda and worker versions. |
| Preconditions and input | Synthetic IDs, key variables, existing payment/appointment/letter state. |
| Actions and messages | Tasklist steps and values; external message name, correlation key and publication time. |
| Expected and actual | Expected entered/skipped elements, actual trace, data records and any difference. |
| Final state | Instance ID, `COMPLETED` or other state, active task and incident counts. |
| Evidence and decision | Status JSON, event log, trace and relevant screenshots; Pass/Fail, defect ID and regression instance ID. |

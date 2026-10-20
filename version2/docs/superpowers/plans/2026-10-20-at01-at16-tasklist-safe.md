# AT-01 to AT-16 Tasklist-Safe Acceptance Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Execute this plan natively in the current session; use test-first steps and verify the whole suite.

**Goal:** Pass all AT-01—AT-16 acceptance scenarios while preserving the real Tasklist browser form-submission demonstration.

**Architecture:** Implement acceptance behavior in an isolated BPMN candidate, the existing deployment-bound forms, and the existing Java JobWorker/executor. Keep the operational Tasklist path and field keys compatible; add only the variables and decisions needed by the acceptance plan. Run acceptance against a dedicated clock-controlled Camunda 8.9 runtime, then deploy the final bundle to a separate/demo-safe process definition and complete a real browser form path.

**Tech Stack:** Camunda 8.9 BPMN, Camunda Forms, Java 21 / Spring Boot JobWorkers, Camunda Process Test, Tasklist browser E2E.

**Spec:** `docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md`; original version 1 plan copied to `source/Hospital_Patient_Pathway_Acceptance_Test_Plan.version1.docx`.

## Global Constraints

- Preserve the AT-01—AT-16 business assertions; do not convert unmet requirements to assumptions, ignored tests, or reduced assertions.
- Keep Tasklist user-task IDs, form IDs, and current field keys stable; extend schemas with additive decision fields.
- Do not mutate the currently shared Camunda runtime or delete its deployments/data.
- Do not overwrite the canonical BPMN while another modeling tool may be editing it; use a separate candidate and reconcile only after source stability is confirmed.
- The acceptance runtime must support advancing and resetting time for AT-14 and must not use shared demo ports/data.
- Keep external participants as deterministic simulated message responders for acceptance; keep the real Tasklist user submissions in browser regression.

## Review Focus

- Browser Tasklist still renders all existing forms and accepts the complete main-path sequence with unchanged variables.
- Time-controlled timer tests exercise real BPMN timer subscriptions and weekly recurrence.
- Every decision form’s output variables are read by the BPMN/worker route that follows.
- Retry and message responses do not get published before their subscriptions activate.
- AT-01 deploys the exact candidate BPMN and the full matching form bundle.

---

### Task 1: Establish safe acceptance inputs and runtime

**Files:**
- Create: `evidence/acceptance-input-v2/` candidate BPMN/forms plus manifest.
- Modify: `java/src/test/java/io/camunda/demo/hpas/Version2AcceptanceIT.java` runtime configuration and snapshot paths.
- Test: acceptance runtime startup/health and exact resource hashes.

- [ ] Copy the current canonical BPMN and forms into a new candidate bundle; record SHA-256 hashes and source timestamps.
- [ ] Start or configure a dedicated Camunda 8.9 runtime with controlled clock on non-demo ports; prove REST, gRPC, and clock control before running IT.
- [ ] Re-run current static tests and frozen snapshot baseline; retain full named results.

### Task 2: Encode missing business decisions in BPMN and forms

**Files:**
- Modify: candidate BPMN and only affected `.form` files.
- Test: add/strengthen behavioral tests in `Version2AcceptanceIT` and static form/BPMN contract tests.

- [ ] AT-05: route by requested scheduling deadline and stop/retry/escalate; preserve slot catch synchronization.
- [ ] AT-07: add funding authorization decision so denial cannot enter treatment/payment.
- [ ] AT-08: check existing charge/payment request before issuing another request; preserve confirmation-lost investigation and finance escalation.
- [ ] AT-09: add explicit clinical stop route with precedence over treatment change/administration.
- [ ] AT-10: order financial recheck before applying treatment change; reject unauthorized changes.
- [ ] AT-11: require identity verification, validate requested date deadline, and contact patient by phone when appointment is within 14 days.
- [ ] AT-13: gate refunds on explicit authorization and route transfers independently.
- [ ] AT-14: make reminders recur every seven days while letter preparation is incomplete; cancel subscriptions when complete.
- [ ] AT-15: correlate restoration to `incidentReference` and verify before reconciliation; retain denied-access audit.
- [ ] AT-16: preserve deployment-bound form references and ensure all added fields render and have stable variable keys.

### Task 3: Implement Java worker contracts

**Files:**
- Modify: `java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java`, `HpasJobWorkers.java`, and persistence only where an acceptance-visible side effect requires it.
- Test: focused worker tests in `OriginalPlanParityIT.java` or the existing unit-test classes.

- [ ] Add deterministic validation for authorization, existing charge idempotency, date-window decisions, transfer outcome, and restoration reference matching.
- [ ] Keep worker failures explicit and prevent worker defaults from inventing clinical/financial authorization.
- [ ] Ensure all BPMN worker types still map one-to-one to Java registration or the explicit SP05 connector.

### Task 4: Verify all acceptance scenarios

**Files:**
- Update: `Version2AcceptanceIT.java`, `OriginalPlanParityIT.java`, `RedesignAcceptanceContractTest.java`, results/manifest documentation.

- [ ] Run focused red/green tests per changed rule on the dedicated controlled-clock runtime.
- [ ] Run full acceptance profile with no skips and no ignored tests.
- [ ] Confirm AT-01—AT-16 each has current-run evidence; report every failure by scenario if any remain.

### Task 5: Protect the Tasklist browser demonstration

**Files:**
- Update only the browser regression script/docs if required; preserve existing demo-specific runtime and test data.

- [ ] Deploy the candidate bundle to a safe demo target without resetting the shared runtime.
- [ ] Use the real Tasklist browser UI to submit the entire normal referral-to-closure path, including every user form in sequence.
- [ ] Verify the original stable field keys remain submitted and the process reaches `Top_End_Closed` without an incident.
- [ ] Record browser screenshots/trace, deployed resource hashes, and final acceptance results separately.

## Verification

- Focused Java worker/unit tests.
- `mvnw -Pacceptance test` against the isolated clock-controlled Camunda 8.9 runtime: all acceptance tests pass, zero skipped.
- Full browser E2E through Tasklist using rendered forms: every user task on the selected complete path submitted; process reaches the expected terminal state.
- Static form schema and BPMN resource/worker/message contract validation against the exact deployed candidate.

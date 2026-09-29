# AISD acceptance test plan

Version: submission 2026-09-29 r2. Environment for recorded results: local Camunda 8.9.19, Java 21 workers, synthetic data. The test cases below are repeatable acceptance scenarios. **Executed** means the cited evidence supports the stated scope; **planned** means no passing runtime claim is made. Design-time FEEL results are labelled separately from Camunda process execution. `TEST_RESULTS.md` is the result register.

## Requirement and business rule catalogue

| ID | Requirement or rule in the executable model | Acceptance measure |
| --- | --- | --- |
| R-01 | One hospital process with external collaboration boundaries | One executable hospital definition deploys with 16 forms; external pools remain non-executable. |
| R-02 / BR-01 | Referral completeness and suitability | `documentsComplete = true` proceeds to review; `referralSuitable = true` proceeds to consultation; other outcomes use documented paths. |
| R-03 / BR-02 | Consultation slot and patient response | Available slot and acceptance lead to consultation; no-slot/rejection routes remain explicit. |
| R-04 / BR-03 | Informed consent | Treatment path requires `informedConsent = true`. |
| R-05 / BR-04 | Services and funding | `allServicesAvailable = true` and, when required, `fundingAuthorised = true` permit continuation. |
| R-06 / BR-05 | Payment and duplicate charge | Paid provider result is recorded; prior charge is checked before a new payment. |
| R-07 / BR-06 | Continue, change or stop treatment | Continue requires `clinicalDecision = "continue"` and `fitToContinue = "yes"`; stop has priority over change. |
| R-08 / BR-07 | Formal treatment change | Formal approval is required; changed cost/funding triggers another check. |
| R-09 / BR-08 | Appointment change | Identity verified; new date is not past due and is within `requestedReviewBy`; within 14 days triggers phone contact. |
| R-10 / BR-09 | Refund or transfer | Refund requires `refundAuthorised = true`; transfer uses separate route. |
| R-11 / BR-10 | Enquiry and correspondence | Enquiries route by category/urgency; letter release requires approval and no suspected clinical error. |
| R-12 / BR-11 | Access and downtime | Access requires `accessAuthorised = "yes"`; restoration uses `incidentReference`. |
| R-13 | Human forms and worker coverage | All 81 collaboration user tasks (70 hospital, 11 external) have a deployment-bound form; every executable hospital job type has a worker. |

The source of each FEEL rule is `1-17.bpmn`. The human meaning of "urgent", suitability and funding policy is not independently specified; do not substitute a fabricated clinical threshold in tests.

## Test cases

### AT-01 - Deployment and resource integrity

- **Requirement/rule:** R-01, R-13.
- **Preconditions:** Fresh Camunda 8.9 compatible environment; only the root `1-17.bpmn` and 16 root `.form` resources selected.
- **Test data:** Bundle revision identified by the repository tag.
- **Actions:** Deploy all resources in one request. Read the deployment response and run `BpmnModelCoverageTest`.
- **Expected/measurable:** Exactly one executable hospital definition and 16 form resources deploy; model validation shows 0 errors and 0 warnings; all 81 collaboration user tasks (70 hospital, 11 external) have form bindings; 50 job types cover 51 executable hospital automated steps.
- **Pass/fail:** Pass only if all counts and validator results match; any duplicate model/form ID, missing worker or deployment error fails.
- **Evidence state:** Executed structurally and deployed locally; `evidence/validation/`, `evidence/runtime/deployment-v4-black-style.json`, `evidence/tests/BpmnModelCoverageTest.txt`.

### AT-02 - Complete, suitable referral reaches the main pathway

- **Requirement/rule:** R-02 / BR-01.
- **Preconditions:** AT-01 passed; workers running; unique synthetic `referralId` and `patientId`.
- **Test data:** `documentsComplete=true`, `referralSuitable=true`, non-urgent referral; use new IDs such as `REF-AT02-001`, `PAT-AT02-001`.
- **Actions:** Start at the hospital Tasklist start, register the referral, complete review, follow the consultation tasks.
- **Expected/measurable:** The instance reaches consultation and can continue to the common end; it does not close on the non-urgent selection alone. No incident or active task remains after full completion.
- **Pass/fail:** Pass when the history shows the consultation path and final `COMPLETED`, `hasIncident=false`, empty active tasks. Otherwise fail.
- **Evidence state:** Executed on a full synthetic route; `evidence/runtime/2251799813799469-status.json` and matching event/trace files.

### AT-03 - Missing referral documents

- **Requirement/rule:** R-02 / BR-01.
- **Preconditions:** New referral with a unique `referralId`; referring-party message simulator available.
- **Test data:** `documentsComplete=false`, then a documents-return message with the same `referralId`.
- **Actions:** Register the referral; observe the missing-document request; publish the return message; complete recheck.
- **Expected/measurable:** The request is issued once, the return correlates to the same instance, and the referral resumes review with no orphan waiting subscription.
- **Pass/fail:** Pass when the intended instance advances and no other instance consumes the message; otherwise fail.
- **Evidence state:** Current Camunda integration test passed (`HospitalPathwayProcessTest`); see `evidence/tests/HospitalPathwayProcessTest.txt` and the clean-test console log.

### AT-04 - Unsuitable referral

- **Requirement/rule:** R-02 / BR-01.
- **Preconditions:** Complete referral at clinical review.
- **Test data:** `documentsComplete=true`, `referralSuitable=false`.
- **Actions:** Complete the suitability decision and inspect the next element and final outcome.
- **Expected/measurable:** The consultation/treatment path is not entered; the model follows its decline/notification route and reaches its defined end.
- **Pass/fail:** Pass when no consultation booking task appears and the decline route completes without incident; otherwise fail.
- **Evidence state:** Current Camunda integration test passed the declined-referral route (`HospitalPathwayProcessTest`); see `evidence/tests/HospitalPathwayProcessTest.txt` and the clean-test console log.

### AT-05 - Consultation availability and escalation

- **Requirement/rule:** R-03 / BR-02.
- **Preconditions:** Suitable referral at scheduling; simulation can return no slot.
- **Test data:** `slotAvailable=false`; one case with a future `requestedReviewBy` that permits retry, and one urgent or expired case.
- **Actions:** Submit scheduling response, advance retry path where allowed, then inspect escalation route for the urgent/expired case.
- **Expected/measurable:** A retry remains inside the requested window; urgent or out-of-window cases do not loop indefinitely and reach an escalation/human decision. Accepted available slot can progress to consultation.
- **Pass/fail:** Pass only when each of the three outcomes follows its stated route with no incident; otherwise fail.
- **Evidence state:** Main available-slot route appears in AT-02 evidence. Scheduling rule cases passed in design-time FEEL evaluation (`evidence/validation/feel-2026-09-29.json`); the no-slot token and escalation acceptance cases remain planned.

### AT-06 - Consent refusal blocks treatment

- **Requirement/rule:** R-04 / BR-03.
- **Preconditions:** Patient assessed at the consent task.
- **Test data:** `informedConsent=false`, unique synthetic patient ID.
- **Actions:** Submit refusal; inspect subsequent elements and active tasks.
- **Expected/measurable:** No treatment booking or treatment execution occurs; the refusal/follow-up route is recorded.
- **Pass/fail:** Pass if zero treatment execution elements appear after refusal and the expected route completes; otherwise fail.
- **Evidence state:** Planned acceptance case. Consent-true route is included in AT-02.

### AT-07 - Unavailable service or declined funding

- **Requirement/rule:** R-05 / BR-04.
- **Preconditions:** Clinically approved treatment request.
- **Test data:** Case A `allServicesAvailable=false`; case B service available, authorisation required, `fundingAuthorised=false`.
- **Actions:** Publish each external response and inspect treatment, funding and follow-up activities.
- **Expected/measurable:** Neither case starts treatment or requests unauthorised payment; each reaches the model's hold/follow-up or alternate outcome with a recorded reason.
- **Pass/fail:** Pass only if both blocked cases avoid treatment and have no incident; otherwise fail.
- **Evidence state:** Planned exception acceptance case. Positive funding route is in AT-02.

### AT-08 - Paid result and prior-charge guard

- **Requirement/rule:** R-06 / BR-05.
- **Preconditions:** Funding approved and payment required; payment simulator active.
- **Test data:** Case A `priorChargeFound=false`, `paymentStatus="paid"`, `providerConfirmsPaid=true`; case B `priorChargeFound=true`.
- **Actions:** Run both cases; compare payment records and route histories.
- **Expected/measurable:** Case A records one successful payment and advances. Case B does not create a second charge for the same payment reference.
- **Pass/fail:** Pass if the distinct payment count is one per reference and the model route matches the guard; otherwise fail.
- **Evidence state:** Positive paid path executed in AT-02; duplicate-charge acceptance remains planned.

### AT-09 - Continuation versus clinical stop

- **Requirement/rule:** R-07 / BR-06.
- **Preconditions:** A treatment cycle has completed; clinical review task active.
- **Test data:** Case A `clinicalDecision="continue"`, `fitToContinue="yes"`; case B `clinicalDecision="stop"`, `action="change"`.
- **Actions:** Submit each decision and inspect following elements.
- **Expected/measurable:** A follows continuation. B follows stop/refund decision, with no treatment-change route despite `action="change"`.
- **Pass/fail:** Pass when the mutually exclusive routes and stop priority are observed; otherwise fail.
- **Evidence state:** Stop/refund route executed on the full path; contradictory stop/change rule passed in design-time FEEL evaluation. Continuation and contradictory token routes remain planned. See AT-02 runtime trace and `evidence/validation/feel-2026-09-29.json`.

### AT-10 - Formal treatment change and cost recheck

- **Requirement/rule:** R-08 / BR-07.
- **Preconditions:** Active treatment plan and a change request.
- **Test data:** Case A `requestStatus="approved"`, `formalClinicalApproval="yes"`, `affectsChargeOrFunding="yes"`; case B unapproved request.
- **Actions:** Submit change request and approvals; inspect cost/funding recheck and terminal state.
- **Expected/measurable:** A routes through the additional cost/funding decision before implementation. B never implements an unapproved plan. Both end without incident.
- **Pass/fail:** Pass only if the trace proves both safeguards; otherwise fail.
- **Evidence state:** Executed sampled alternatives; `evidence/runtime/2251799813803956-status.json` and `2251799813803472-status.json` with matching event/trace files.

### AT-11 - Appointment change identity, date and contact rule

- **Requirement/rule:** R-09 / BR-08.
- **Preconditions:** Existing appointment and change request.
- **Test data:** Cases: `identityVerified="no"`; verified with new date after `requestedReviewBy`; verified with date within 14 days; verified with date more than 14 days away.
- **Actions:** Submit each case in a new instance, inspect slot decision, contact task and notification.
- **Expected/measurable:** Unverified request cannot change appointment; late slot is rejected; near-term change requires phone contact before ordinary notice; later valid slot follows normal notice.
- **Pass/fail:** Pass only if all four route checks match the model with no incident; otherwise fail.
- **Evidence state:** The unverified-identity negative route passed in `HospitalPathwayProcessTest.unverifiedAppointmentChangeCannotUpdateBooking`; it returns to request correction without updating the booking. See `evidence/tests/TEST-io.camunda.demo.hospital.HospitalPathwayProcessTest.xml` and `negative-branch-integration-2026-09-29.log`. Date and 14-day boundary rules passed in design-time FEEL evaluation; the three verified-date/contact token cases remain planned.

### AT-12 - Cancellation, clinical review and no-show

- **Requirement/rule:** R-03 and exception handling.
- **Preconditions:** Confirmed appointment in a synthetic case.
- **Test data:** Separate cancel/rebook, clinical-review and no-show cases.
- **Actions:** Publish the relevant event and complete the assigned human tasks.
- **Expected/measurable:** Each case reaches its distinct outcome; no active task or incident remains in the completed sampled instance.
- **Pass/fail:** Pass when all three instance states are `COMPLETED` with empty tasks/incidents and traces show different routes; otherwise fail.
- **Evidence state:** Executed sampled alternatives; `evidence/runtime/2251799813805423-status.json`, `2251799813810277-status.json`, `2251799813811466-status.json` and matching traces.

### AT-13 - Authorised refund versus transfer

- **Requirement/rule:** R-10 / BR-09.
- **Preconditions:** Financial adjustment decision task active.
- **Test data:** A `refundOutcome="refund"`, `refundAuthorised=true`; B `refundOutcome="transfer"`.
- **Actions:** Complete each decision and examine payment/refund and new-booking records.
- **Expected/measurable:** A invokes the authorised refund route and records result; B records transfer without invoking refund. Both sampled instances finish without incident.
- **Pass/fail:** Pass if each route and final status matches; fail for an unauthorised refund or crossed routes.
- **Evidence state:** Executed sampled alternatives; `evidence/runtime/2251799813803956-status.json`, `2251799813811822-status.json` and matching traces. The unauthorised-refund rule passed in design-time FEEL evaluation; a separate Camunda negative case remains planned.

### AT-14 - Enquiry classification and letter controls

- **Requirement/rule:** R-11 / BR-10.
- **Preconditions:** New patient enquiry or pending clinic letter.
- **Test data:** Finance enquiry; approved letter with `consultantApproved="yes"`, `noSuspectedClinicalError="yes"`; suspected-error case; outstanding letter through reminder intervals.
- **Actions:** Submit enquiry and letter tasks; inspect routing, correspondence message and timer/reminder history.
- **Expected/measurable:** Finance enquiry reaches finance route; approved safe letter is released; suspected-error letter is held/reviewed; outstanding letter reminds at model-defined intervals and a completed letter cancels further waiting.
- **Pass/fail:** Pass only if each route and timing condition is observed; otherwise fail.
- **Evidence state:** Finance enquiry and approved/completed letter sampled at runtime (`2251799813808280`, `2251799813799469`). The suspected-error rule passed in design-time FEEL evaluation; its token route, full-duration timers and concurrent letters remain planned.

### AT-15 - Access and downtime recovery

- **Requirement/rule:** R-12 / BR-11.
- **Preconditions:** Access request and separately an interruption with unique `incidentReference`.
- **Test data:** Approved and denied access values; `INC-AT15-001` for downtime/restoration.
- **Actions:** Submit access decision; report interruption; record offline work; publish service-restored message with matching incident reference; reconcile.
- **Expected/measurable:** Approved access follows authorised path; denied access cannot enter protected action; restoration correlates to the right instance and leaves no active incident/task after reconciliation.
- **Pass/fail:** Pass if both access routes and recovery correlation match with final completed status; otherwise fail.
- **Evidence state:** Approved access and downtime recovery sampled at runtime (`2251799813808708`, `2251799813809073`). The denial negative route passed in `HospitalPathwayProcessTest.deniedAccessIsAuditedWithoutGrant`; see `evidence/tests/TEST-io.camunda.demo.hospital.HospitalPathwayProcessTest.xml` and `negative-branch-integration-2026-09-29.log`.

### AT-16 - Form rendering and worker contract

- **Requirement/rule:** R-13.
- **Preconditions:** Master BPMN, forms and worker source from the tagged revision.
- **Test data:** All 81 collaboration user tasks (70 hospital, 11 external) and all executable hospital job types.
- **Actions:** Run form binding audit, form-js render/submit audit and `BpmnModelCoverageTest`; compare model job types with `java/WORKER_MAP.md`.
- **Expected/measurable:** 81/81 forms render and submit the active group; 0 duplicate form IDs; all 50 job types have a worker and all 51 executable automated steps are covered.
- **Pass/fail:** Pass only if each count is complete and 0 model/worker mismatches remain; otherwise fail.
- **Evidence state:** Executed structurally; `evidence/validation/form-mapping-audit.json`, `evidence/validation/form-browser-check.json`, `evidence/tests/BpmnModelCoverageTest.txt`.

## Acceptance decision

The current evidence supports a **local prototype demonstration**, not unconditional production acceptance. The assessor should run the remaining AT-05 to AT-08 negative cases, AT-11 verified-date/contact routes, the token-level suspected-error and timer/concurrent-letter portions of AT-14, plus the pending AT-09 and AT-13 negative routes before marking every criterion passed. Record actual instance IDs and observed outcomes in `TEST_RESULTS.md` or a dated addendum; do not convert planned cases to pass based only on FEEL evaluation or static model inspection.

# Manual Test Walkthrough

[简体中文](MANUAL_TEST_GUIDE.zh-CN.md) | **English**

After one deployment, can a person click the whole pathway through? Which steps wait for an external
message, which need somebody in Tasklist, and what has to be filled in or published at each step?
Every element id, message name, correlation key and field name below comes from
[`1-17.bpmn`](1-17.bpmn), and every command was run on the local c8run (results in section 10).

---

## 1. Prerequisites: three things must be in place

| Component | How to check | Command |
| --- | --- | --- |
| Cluster | gRPC 26500 / REST 8080 listening | `netstat -ano \| findstr "26500 8080"` |
| Model + 15 forms | deployment returns 200 | deployment command in [`README.md`](README.md) |
| Worker application (required) | process is running | `jps -l \| findstr HospitalPathway` |

Two UIs: **Tasklist** <http://localhost:8080/tasklist> (human tasks) and **Operate**
<http://localhost:8080/operate> (instances, incidents, the element an instance is parked on).

Fastest health check, especially after a machine/cluster restart: `POST /v2/jobs/search` with
`{"filter":{"state":"CREATED"}}` must return **0** jobs. Anything listed means no worker is consuming -
the application is not running (or cannot reach the cluster), and every `service`/`send` step will sit
still until it is. The H2 data survives a restart (deployments and instances are still there).

## 2. Demo mode: just run the application

| | Demo mode (default configuration) |
| --- | --- |
| Worker application | **running**: `mvn spring-boot:run` |
| Automated steps (service / send tasks) | the workers of the application complete them |
| Answers from patient / laboratory / external parties | the application answers on their behalf: the requested ones (documents, slot, external service, funding, payment, refund, letters) are published by the matching worker; the **four that nobody requests** (patient preference, patient consent, test results, service restored) are published every 10 seconds by the auto-responder (`auto-responder-enabled=true`, on by default) |
| **What the human does** | only complete the tasks in Tasklist and pick the right field values |

⚠️ **Use the default ids when starting an instance**: the auto-responder always uses
`hospital.simulation.default-referral-id` / `default-patient-id` (`REF-1001` / `PAT-1001`). Messages
match on name **and** correlation key, so start the instance with those two ids (section 3) and every
later wait state is answered automatically - **nothing has to be published by hand during a demo**.

**Key prerequisite**: service / send tasks are **jobs**; only a worker (or REST) can move them on. You
cannot complete them in Tasklist, so the application must be running during a demo. To verify a single
step, or to watch what happens without an application, use appendix A (section 7).

## 3. Starting an instance: one click in the UI (no message needed)

The model's only **plain start event** is the entry of process 1, `Start_Tasklist` ("Start referral in
Tasklist" - the Medical Secretaries take the referral into the hospital system, case study step 1.1),
so "Start instance / Start process" in Operate or Tasklist without `startInstructions` enters process 1.

1. Operate → Processes → select `Hospital Patient Referral…` → **Start instance** (the Processes page of Tasklist works too)
2. Put this into the variables box (**default ids**, so the auto-responder matches):

```json
{"referralId":"REF-1001","patientId":"PAT-1001","patient_name":"Alex Morgan"}
```

3. The first task in Tasklist is then `R1_RegisterCheck` (assignee `medical-secretary`) - open it and start walking.

Every other entry point is a **message** start event (`Incoming referral package`, `Next treatment cycle
due`, one per process 9-16). To look at those satellite paths (for example the laboratory wait of
process 8), use the scenarios that ship with the application or the message commands in appendix A -
the demo of the main pathway needs none of them.

## 4. The happy path, step by step

In Tasklist: if a task is not visible under "Assigned to me", switch to **All open tasks** and
**Claim** it before you complete it. "Who" is the `assignmentDefinition` assignee / candidate group.

| # | Where / element | Who | What to do, what matters |
| --- | --- | --- | --- |
| 1 | Phase 1 `Phase_ReceiveReferral` · `R1_RegisterCheck` | medical-secretary | form `01_receive_referral`; **set `documentsCompleteChoice` to Yes** → the phase ends and phase 2 starts. No/unset → the missing-documents loop (section 4.1) |
| 2 | Phase 2 `Phase_ReviewReferral` · `R2_Review` | consultant | this asks whether the referral is clinically suitable, not whether it is urgent: choose **Yes** for a suitable routine referral, then choose Routine in `R2_Route`; choose No only when it is clinically unsuitable |
| 3 | Gateway `Gateway_ReferralSuitable` | - | reads `referralSuitable = true` → phase 3 |
| 4 | Phase 3 `Phase_InitialConsultation` | see below | ① waits for `Patient appointment preference` (**answered by the auto-responder**) ② worker `request-consultation-slot` publishes `Initial consultation slot request` ③ waits for `Initial consultation slot availability` (**published by the worker**) ④ `R3_RecordAvailability` (booking-clerk, form `03_initial_consultation`, **`availability` = available**) ⑤ publishes `Proposed consultation appointment` ⑥ event gateway `R3_WaitPatientDecision` waits for one of three (**all answered by the auto-responder**): `Patient appointment response` (accepted → `R3_Booked`) / `Alternative consultation date` (asks for another date → back to ②) / `Patient declines consultation` (→ `R3_Declined`, phase ends) |
| 5 | Gateway `Gateway_PatientConfirmed` | - | reads `appointmentAccepted = true` (written by those two catch events) → phase 4 |
| 6 | Phase 4 `Phase_DiagnosisConsent` | consultant / clinical-team | `R4_Assess` → `R4_Explain` → waits for `Patient consent decision` (**answered by the auto-responder, payload carries `informedConsent: true`**) → gateway `R4_Consent` → `R4_RecordConsent` |
| 7 | Gateway `Gateway_ConsentRecorded` | - | reads `informedConsent = true` → phase 5 |
| 8 | Phase 5 `Phase_ArrangeTreatment` | treatment-booking-team | `R5_ReviewPlan` → worker `request-treatment-service` publishes `Treatment or external service request` → waits for `Treatment or external service availability` (**published by the worker, carries `allServicesAvailable: true`**) → `R5_ConfirmTreatment` |
| 9 | Phase 6 `Phase_VerifyCost` | finance | `R6_ClassifyFunding` (**`externalAuthorisationRequiredChoice` and `paymentRequiredChoice` decide whether authorisation and payment happen at all**) → if authorisation is needed: worker `request-funding-authorisation` publishes `Funding authorisation request` → waits for `Funding authorisation decision` (**published by the worker, carries `fundingAuthorised: true`**) → `R6_RecordAllocation` (**`fundingApprovedChoice` = Yes**) |
| 10 | Gateways `Gateway_FundingApproved` → `Gateway_PaymentRequired` | - | `fundingApproved = true`; only `paymentRequired = true` enters phase 7 (otherwise straight to `Confirm_Treatment_Booking`) |
| 11 | Phase 7 `Phase_ProcessPayment` | finance | `R7_CalculateCharge` → worker `request-payment` publishes `Secure payment request` → waits for `Payment result from provider` (**published by the worker, carries `paymentStatus: "paid"`**) → gateway `R7_PaymentPaid` → `R7_RecordPayment` |
| 12 | Gateway `Gateway_PaymentConfirmed` | - | `paymentStatus = "paid"` → `Confirm_Treatment_Booking` (human, form `05_treatment_services`) → `Deliver_Treatment_Cycle` (human) |
| 13 | Phase 8 `Phase_ContinueTreatment` · `R8_ClinicalReview` | clinical-team | form `08_continue_treatment`; **`clinicalDecision`** = `continue` / `change` / `delay` / `stop` |
| 14 | Gateway `Gateway_ClinicalDecision` | - | `continue`/`change` → back to phase 5 (next treatment cycle, a loop); `delay` → timer `= reassessmentDelay`; default (stop) → `Finance_RefundReview` (the refund branch) |

Loop: every treatment cycle repeats 8 → 13 → 14 until `clinicalDecision` is `delay` (wait for
reassessment) or `stop` (refund).

### 4.1 The missing-documents loop of phase 1 (the most common "it is stuck")

```
R1_RegisterCheck → R1_Complete "Required information complete?"
   ├ Yes (= documentsComplete = true) → R1_Ready (phase ends)
   └ default → R1_RequestDocs (job send-referral-documents-request, publishes "Requested referral documents")
            → R1_ReceiveDocs (waits for the message "Requested referral documents", key = referralId)
            → R1_UpdateReferral (human, medical-secretary, decides again) → back to R1_Complete
```

## 5. Branch reference: what every gateway reads and where that value comes from

"Source" = how the variable is produced: **form** = a field the human fills in Tasklist (the model's
ioMapping turns it into the variable); **message** = it must arrive in an external message payload;
**model** = the model writes it itself.

| Gateway (phase) | Variable | Source | Value → effect |
| --- | --- | --- | --- |
| `R1_Complete` (1) | `documentsComplete` | form `01`: `documentsCompleteChoice = "yes"` | yes → phase ends; otherwise → missing-documents loop |
| `R2_Suitable` (2) | `referralSuitable` | form `02`: `referralSuitableChoice = "yes"` | yes → set priority; no → decline and answer the referrer |
| `Gateway_ReferralSuitable` | same | same | `referralSuitable` is exported from the phase 2 subprocess; clinically suitable (Yes) → phase 3 for either Urgent or Routine; unsuitable (No) → end (`End_ReferralNotSuitable`) |
| `R3_SlotAvailable` (3) | `slotAvailable` | form `03`: `availability = "available"` | available → send the offer; otherwise → wait and retry |
| `R3_WaitPatientDecision` (3) | event gateway | - | whichever arrives first: `Patient appointment response` / `Alternative consultation date` / `Patient declines consultation` |
| `Gateway_PatientConfirmed` | `appointmentAccepted` | model (two catch events write true/false) | true → phase 4; otherwise the process ends (`End_PatientDeclined`) |
| `R4_Consent` (4) | `informedConsent` | **message** `Patient consent decision` payload | true → record consent; otherwise record refusal → process ends |
| `Gateway_ConsentRecorded` | same | same | true → phase 5; otherwise the process ends |
| `R5_AllAvailable` (5) | `allServicesAvailable` | **message** `Treatment or external service availability` payload | true → confirm booking; otherwise → wait and retry (timer `= treatmentServiceRetryDelay`) |
| `R6_AuthNeeded` (6) | `externalAuthorisationRequired` | form `06`: `externalAuthorisationRequiredChoice = "yes"` | yes → request insurer authorisation; otherwise record directly |
| `R6_AuthApproved` (6) | `fundingAuthorised` | **message** `Funding authorisation decision` payload | true → record allocation; otherwise → `R6_ResolveGap` (funding gap) |
| `Gateway_FundingApproved` | `fundingApproved` | form `06`: `fundingApprovedChoice = "yes"` (`R6_ResolveGap` always writes false) | true → check for advance payment; otherwise the process ends (`End_FundingHold`) |
| `Gateway_PaymentRequired` | `paymentRequired` | form `06`: `paymentRequiredChoice = "yes"` | yes → phase 7; no → confirm the treatment booking directly |
| `R7_PaymentPaid` (7) | `paymentStatus` | **message** `Payment result from provider` payload (`"paid"`) or the `R7_RecordPayment` form | paid → record the payment; otherwise → the retry/reconciliation branch |
| `R7_RetryAllowed` (7) | `retryApproved` | form `07`: `retryApprovedChoice = "yes"` | yes → check for a duplicate charge (job `check-payment-idempotency`); otherwise → flag for investigation |
| `R7_NoChargeFound` (7) | `priorChargeFound` | worker `check-payment-idempotency` writes it when completing the job | false → send the payment request again; true → flag for investigation |
| `R7_ReconciledPaid` (7) | `providerConfirmsPaid` | form `07`: `providerConfirmsPaidChoice = "yes"` | true → record the payment; otherwise → unresolved, treatment on hold |
| `Gateway_PaymentConfirmed` | `paymentStatus` | as above | `"paid"` → confirm the treatment booking; otherwise `End_PaymentHold` |
| `Gateway_ClinicalDecision` (8) | `clinicalDecision` | form `08` | `continue`/`change` → back to phase 5; `delay` → timer; default (stop) → refund branch |
| `Gateway_RefundAuthorized` | `refundAuthorised` | form `07`: `refundAuthorisedChoice = "yes"` | yes → send the refund request → wait for `Refund result from provider` → `Finance_RecordRefund`; otherwise the end |
| P8 `P8_Gateway_Fit` / `P8_Gateway_Action` | `fitToContinue` / `action` | form `08` (`yes` / `change`) | decide whether the next cycle goes ahead or is postponed/changed |
| P9 `P9_Gateway_Type` | `requestStatus` | form `09` (`approved` / `urgent` / anything else) | schedule change / urgent postponement / not processed |
| P9 `P9_Gateway_Costs` | `affectsChargeOrFunding` | form `09` (`yes`) | yes → finance review |
| P10 `P10_Gateway_Decide` | `caseDecision` | form `10`, task `P10_Task_Record` (`re-book` / `clinical-review` / `notify-referrer`) | selects re-book, clinical review or notify; all branches rejoin at the payment decision and end |
| P10 `P10_Gateway_Paid` | `paymentReceived` | form `10` (`yes`) | yes → finance decides retain/transfer/refund |
| P11 `P11_Gateway_Outcome` | `refundOutcome` | form `11` (`refund` / `transfer` / anything else = refused) | refund, transfer or refusal |
| P12 three gateways | `urgentConcern`, `enquiryCategory` | form `12` (`yes`; `admin-simple` / `admin-other` / `finance` / anything else) | urgent flag, transfer to a team, referral to finance |
| P13 `P13_Gateway_0utxesn` | `consultantApproved` | form `13` (`yes`) | yes → send the letter to the Medical Secretaries; otherwise back to the review |
| P13 `P13_Gateway_1uxwgi9` | `noSuspectedClinicalError` | form `13` (`yes`) | yes → distribute the letter (writes `letterComplete = true`); otherwise return it to the consultant |
| P13 three due-date gateways | `letterComplete` | model (draft writes false, distribution writes true) | false → overdue reminders/escalation (7 days / 1 month / 3 months) |
| P14 `P14_Gateway_Slot` | `slotAvailable` | form `14` (`yes`) | yes → book the follow-up automatically; otherwise hand it to the pathway team |
| P16 `P16_Gateway_Access` | `accessAuthorised` | form `16` (`yes`) | yes → grant the permissions; otherwise straight to the audit step |

## 6. Message catalogue: who publishes, the correlation key, the payload it must carry

**Every row is published by the application** - by the matching worker or by the auto-responder; a demo
never needs a manual publication. (Appendix A has the commands for debugging, for example when running
without an application.)

| Message name | Who | Correlation key | Key variables in the payload |
| --- | --- | --- | --- |
| `Incoming referral package` | external (referring organisation) | `referralId` | `referralId`, `patientId`, `patient_name` |
| `Next treatment cycle due` | hospital system (next treatment cycle due, starts process 8) | no correlation key | `referralId`, `patientId`, `cycleNumber` (P8 needs `patientId` later) |
| `Requested referral documents` | application (`send-referral-documents-request` worker) | `referralId` | `receivedDocumentReferences`, `referralUpdateNotes` |
| `Referral review outcome` | application (`send-referral-outcome`) | `referralId` | `referralOutcome` |
| `Patient appointment preference` | patient | `referralId` | `preferredDate` and friends (optional) |
| `Initial consultation slot request` | application (`request-consultation-slot`) | `referralId` | - |
| `Initial consultation slot availability` | external (scheduling) | `referralId` | `availability`, `date_of_appointment`, `appointmentTime` |
| `Proposed consultation appointment` | application (`send-appointment-confirmation`) | `referralId` | - |
| `Patient appointment response` | patient | `referralId` | (the model writes `appointmentAccepted = true`) |
| `Alternative consultation date` | patient | `referralId` | `alternativeAppointmentDate` |
| `Patient declines consultation` | patient | `referralId` | (the model writes `appointmentAccepted = false`) |
| `Patient consent decision` | patient | `referralId` | **`informedConsent: true`** (false/missing → refusal branch) |
| `Treatment or external service request` | application (`request-treatment-service`) | `referralId` | - |
| `Treatment or external service availability` | external (lab/imaging) | `referralId` | **`allServicesAvailable: true`** |
| `Funding authorisation request` | application (`request-funding-authorisation`) | `referralId` | - |
| `Funding authorisation decision` | external (insurer/funder) | `referralId` | **`fundingAuthorised: true`** |
| `Secure payment request` | application (`request-payment`) | `referralId` | - |
| `Payment result from provider` | external (payment provider) | `referralId` | **`paymentStatus: "paid"`** |
| `Authorised refund request` | application (`submit-authorised-refund`) | `referralId` | - |
| `Refund result from provider` | external (payment provider) | `referralId` | `refundStatus`, `refundedAmount` |
| `TestResultsMessage` | laboratory | **`patientId`** | `fitToContinue: "yes"` |
| `LetterFromDoctor` / `LetterBackForRechecking` / `MessageFromDoctor` / `ReportFromDoctor` | application (letter workers) | **`patientId`** | the letter handling result |
| `TreatmentChangeRequestMessage` | external | no correlation key | `referralId`, `patientId` (recommended) |
| `AppointmentCancellationReportMessage` | external | no correlation key | same |
| `RefundCaseReceivedMessage` | external | no correlation key | same |
| `PatientQuestionMessage` | patient | no correlation key | same |
| `ClinicVisitCompletedMessage` | external (clinic) | **`patientId`** | `patientId` |
| `FollowUpRequestedMessage` | consultant | no correlation key | `referralId`, `patientId` |
| `Monitoring run or report request` | external | no correlation key | - |
| `Staff identity or access change request` | external | no correlation key | `targetStaffUserId`, `requestedRole` |
| `System or external-service interruption` | external | no correlation key | `incidentReference`, `affectedService` |
| `System or external service restored` | external | `incidentReference` | `incidentReference` (must match the interruption) |

## 7. Appendix A: manual debugging commands (not needed for a demo)

```bash
# start an instance (same as "Start instance" in the UI; the default ids let the auto-responder match)
curl -s -X POST http://localhost:8080/v2/process-instances -H "Content-Type: application/json" \
  -d '{"processDefinitionId":"Process_Hospital_Merged","variables":{"referralId":"REF-1001","patientId":"PAT-1001","patient_name":"Alex Morgan"}}'

# publish a message (template: swap name / correlationKey / variables; a message without a correlation
# key - the P9-P16 family - takes no correlationKey)
curl -s -X POST http://localhost:8080/v2/messages/publication -H "Content-Type: application/json" \
  -d '{"name":"Next treatment cycle due","variables":{"referralId":"REF-1001","patientId":"PAT-1001","cycleNumber":2}}'
# to pre-answer a wait: add "timeToLive": 600000 (milliseconds); the engine buffers the message and
# correlates it the moment the subscription appears

# complete a job by hand (when the application is not running)
curl -s -X POST http://localhost:8080/v2/jobs/activation -H "Content-Type: application/json" \
  -d '{"type":"send-referral-documents-request","maxJobsToActivate":1,"timeout":60000,"worker":"manual-tester"}'
curl -s -X POST http://localhost:8080/v2/jobs/<jobKey>/completion -H "Content-Type: application/json" -d '{"variables":{}}'
# ⚠️ a send task means "publish a message": completing the job by hand does not publish it

# lookups: active instances / the elements one instance walked / open tasks / incidents
curl -s -X POST http://localhost:8080/v2/process-instances/search -H "Content-Type: application/json" -d '{"filter":{"state":"ACTIVE"}}'
curl -s -X POST http://localhost:8080/v2/element-instances/search -H "Content-Type: application/json" -d '{"filter":{"processInstanceKey":"<instance key>"}}'
curl -s -X POST http://localhost:8080/v2/user-tasks/search -H "Content-Type: application/json" -d '{"filter":{"state":"CREATED"}}'
curl -s -X POST http://localhost:8080/v2/incidents/search -H "Content-Type: application/json" -d '{}'
```

In PowerShell `curl` is the `Invoke-WebRequest` alias - write `curl.exe` and escape the double quotes
inside the JSON (`\"`).

## 8. Troubleshooting

1. Operate → Processes → tick **Incidents** → open the instance → look at the current element and the
   incident message.
2. Fast lookups over REST (all verified locally):

```bash
curl -s -X POST http://localhost:8080/v2/incidents/search -H "Content-Type: application/json" -d '{}'
curl -s -X POST http://localhost:8080/v2/element-instances/search -H "Content-Type: application/json" -d '{"filter":{"processInstanceKey":"<instance key>"}}'
curl -s -X POST http://localhost:8080/v2/user-tasks/search -H "Content-Type: application/json" -d '{}'
```

| Symptom | Cause | What to do |
| --- | --- | --- |
| parked on a user task, no incident | normal waiting | complete the task in Tasklist (pick the right field values) |
| `EXTRACT_VALUE_ERROR ... correlation key ... NULL` | the instance is missing a correlation variable (usually `patientId` / `referralId` did not come with the start) | cancel and restart with the variables (the key is computed when the subscription is created, so adding data later needs a modification + retry) |
| incident: no worker subscribed to the job type | the worker application is not running or does not subscribe that type | start the application, then retry the incident |
| the instance ends right after a gateway | it took the default flow (the variable never became the required value) | check the variable's source and value against section 5 |

## 9. Known gaps in the model (a manual tester will hit them)

| Symptom | Cause (verified) | Workaround |
| --- | --- | --- |
| P10's next-action choices are missing | `caseDecision` is now a required field in form `10`'s `P10_Task_Record`, and the BPMN maps it into and out of the task before `P10_Gateway_Decide` | redeploy the updated BPMN and form; select `re-book`, `clinical-review` or `notify-referrer` |
| phases 4/5/6/7 have no form switch for their "Yes" branch | `informedConsent`, `allServicesAvailable`, `fundingAuthorised`, `priorChargeFound` are **not form fields**; they must arrive with an external answer | demo mode: the application supplies them (auto-responder / matching worker); only a manual publication needs them written out (appendix A) |
| P8/P13 wait states raise an incident immediately | `patientId` only comes from outside; the model never produces it (five correlation keys read it) | pass `patientId` when starting the instance (use the default `PAT-1001` so the auto-responder matches) |
| phase 1 does not move on after filling the form | `documentsCompleteChoice` was not set to Yes → the missing-documents loop (by design) | set it to Yes in the form |
| (fixed) an instance started with "Start instance" used to land in process 8 | at the time, the only plain start event was `P8_Start_CycleDue` | the only plain start event is now `Start_Tasklist` (process 1); process 8 is triggered by the message `Next treatment cycle due` (see row 7 of the model repairs in `README.md`) |
| the instance parks on `R3_PatientPreference` / `R4_ReceiveConsentDecision` | the four patient-side messages are published by the auto-responder, but always with the configured `REF-1001` / `PAT-1001`; an instance carrying other ids never matches | start the instance with the default ids (section 3) and it advances automatically |
| process 8 keeps sitting on `P8_Catch_Results` | it waits for the message `TestResultsMessage` (correlation key `= patientId`): the auto-responder publishes it every 10 seconds, but with the **default id `PAT-1001`**; an instance whose `patientId` differs never matches (messages match on name **and** correlation key) | start with the default id (`PAT-1001`) and it advances by itself; if the instance already uses another id, cancel and restart it (or publish that one message by hand per appendix A) |
| wait or incident? | without a `patientId` variable the instance raises an incident the moment the subscription is created (`EXTRACT_VALUE_ERROR`) instead of waiting | a red ❗ in Operate = incident (fix the data); an instance that just sits there = a normal wait (waiting for the auto-responder, or a branch that was never triggered) |

## 10. What was verified here

| Check | Command | Result |
| --- | --- | --- |
| plain Start instance enters process 1 | `POST /v2/process-instances` (without `startInstructions`) | HTTP 200, element path `Start_Tasklist → Phase_ReceiveReferral → R1_RegisterCheck` |
| message entry of process 8 | `POST /v2/messages/publication`, `Next treatment cycle due` | HTTP 200, new instance path `P8_Start_CycleDue → P8_Task_Review` |
| deploy model + 15 forms | `POST /v2/deployments` | HTTP 200, `Process_Hospital_Merged` + 15 forms |
| message channel | `POST /v2/messages/publication` | HTTP 200 (returns a `messageKey`) |
| manual job completion channel | `POST /v2/jobs/activation` | HTTP 200 (`{"jobs":[]}`) |
| task lookup channel | `POST /v2/user-tasks/search` | HTTP 200 |
| incident / element instance lookup | `POST /v2/incidents/search`, `/v2/element-instances/search` | HTTP 200 (found your `P8_Catch_Results` incident) |
| worker project self-test | `mvn -o test` | 12/12 passed (7 model consistency + 5 real cluster) |
| the auto-responder is on by default | `mvn spring-boot:run` (no arguments) + completing `P8_Task_Review` by hand | the instance crossed `P8_Catch_Results` **without any manual message**: `P8_Task_Review COMPLETED 15:22:52 → P8_Catch_Results COMPLETED → P8_Task_Evaluate ACTIVE`, 0 incidents |
| the suite is still green with the new default | `mvn -o test` | 12/12 passed (`HospitalPathwayProcessTest` sets `auto-responder-enabled=false` explicitly, to stay deterministic) |
| full pathway end to end | `python _analysis/walkthrough_driver.py` (section 11) | 18 human tasks + the application answering every external wait → instance `COMPLETED` at `End_TreatmentStopped`, ≈ 86 s, 0 incidents |
| the message starts of processes 9-16 | `python _analysis/satellite_demo.py all` (section 12.1) | nine instances, each landing where the model says (p15 finished by itself in ~0.15 s) |
| the three timers of process 13 | `python _analysis/timer_demo.py deploy` + `p13` (section 12.2) | `Wait 7 days` → `Wait 23 days` → `Wait 2 months` all fired and the escalation chain reached senior review |
| the reassessment timer of phase 8 | `HOSPITAL_CLINICAL_DECISION=delay HOSPITAL_REASSESSMENT_DELAY=PT20S python _analysis/walkthrough_driver.py --wait 115` (section 12.3) | `Timer_Reassessment` COMPLETED after 20.3 s and the flow re-entered process 8 |

## 11. Automated walkthrough: one instance from process 1 to the end

`_analysis/walkthrough_driver.py` drives a real instance over REST: it completes **only the human
tasks** (with plausible form values) and lets the application do everything else - the job workers and
the 10 s auto-responder. It is the fastest proof that the pathway runs end to end.

```bash
cd _analysis
python walkthrough_driver.py                 # start a new instance (default ids) and walk it
python walkthrough_driver.py 2251799...      # walk an instance that is already running
```

Verified on 2026-09-27 (cluster up, application running, model deployed):

| Check | Result |
| --- | --- |
| steps (human tasks completed by the script) | **18** - `R1_RegisterCheck` → … → `Finance_RecordRefund` |
| wall clock | 15:31:26 → 15:32:52 (≈ 86 s; each patient/laboratory wait costs one 10 s auto-responder tick) |
| final state | `COMPLETED`, last element `End_TreatmentStopped` |
| incidents | **0** |

Every wait that no human owns was answered by the application, without a single manual message:
`Patient appointment preference` → `Initial consultation slot availability` → `Patient appointment
response` (phase 3), `Patient consent decision` (phase 4), `Treatment or external service availability`
(phase 5), `Funding authorisation decision` (phase 6), `Payment result from provider` (phase 7),
`Refund result from provider` (refund branch). Full log: `_analysis/walkthrough-run-full-path.txt`.

Notes

- the script starts the instance with the default ids, so the auto-responder matches (section 2);
- `R8_ClinicalReview` is completed with `clinicalDecision = "stop"` so the instance can end; set it to
  `continue` if you want to demo the next treatment cycle (the flow then loops back to phase 5);
- `--only R5_ReviewPlan,R8_ClinicalReview` completes just those tasks and leaves the rest open, and the
  environment variables `HOSPITAL_CLINICAL_DECISION` / `HOSPITAL_REASSESSMENT_DELAY` change the phase-8
  outcome without editing the file (section 12.3);
- everything the driver sends is typed/selected in the task's form: `_analysis/check_form_inputs.py`
  cross-checks the map against the model's `formDefinition` references and the 15 `.form` files and
  reports **0 mismatches** (variables that no form can produce are listed separately and marked as coming
  from a message, a worker or the model's ioMapping) - so clicking through Tasklist yields the same
  branch decisions as this automated run;
- it only touches its own instance, so it can run while you click a demo of your own.

## 12. Demoing the satellite processes (message starts) and the timers

### 12.1 One message = one satellite process

Every entry point except `Start_Tasklist` is a **message** start event, so a single published message
spins up an instance that runs that process on its own. `_analysis/satellite_demo.py` does exactly that
and reports where the instance landed:

```bash
cd _analysis
python satellite_demo.py --list                 # the table of start messages
python satellite_demo.py p15                    # fire one (report it, then cancel the instance)
python satellite_demo.py p13 --keep             # fire it and leave the instance for clicking
python satellite_demo.py all                    # all nine, one report
```

Measured on 2026-09-27 (model deployed, application running) - "landed" is the first human task the
instance waits for:

| case | start message | landed on | notes |
| --- | --- | --- | --- |
| `p9` | `TreatmentChangeRequestMessage` | `P9_Task_AdjustSchedule` | `requestStatus: "approved"` picks the "formally approved" branch |
| `p10` | `AppointmentCancellationReportMessage` | `P10_Task_Record` | `caseDecision` selects re-book, clinical review or notify; then `paymentReceived` routes to Finance or the end |
| `p11` | `RefundCaseReceivedMessage` | `P11_Task_Decide` | `refundOutcome` picks refund / transfer / refusal |
| `p12` | `PatientQuestionMessage` | `P12_Activity_13m3bda` | `enquiryCategory` routes to admin / finance / clinical |
| `p13` | `ClinicVisitCompletedMessage` | `P13_Activity_0az5u78` **and** `P13_Event_1o398fk` (Wait 7 days) | the letter work and the timer chase run **in parallel** |
| `p14` | `FollowUpRequestedMessage` | `P14_Task_Receive` | `slotAvailable: "yes"` books the follow-up automatically |
| `p15` | `Monitoring run or report request` | - | **fully automatic**: collect → generate → publish, `COMPLETED` in ~0.15 s, no human task |
| `p16-access` | `Staff identity or access change request` | `P16_Task_Authenticate` | then grant + protected audit log |
| `p16-interruption` | `System or external-service interruption` | `P16_Task_RecordInterruption` | carry `incidentReference: "INC-1001"` so the auto-responder's "service restored" matches |

Start messages take **no** correlation key (they create the instance). Without `--keep` the script cancels
the instance again after reporting, so it can be fired repeatedly during a demo.

### 12.2 The three timers of process 13 (7 days / 23 days / 2 months)

They are literal durations (`P7D`, `P23D`, `P2M`), so a demo needs a shortened copy of the model.
`_analysis/timer_demo.py` writes that copy **outside the bundle** (`bpmn/_timer-demo/1-17-fast-timers.bpmn`),
so the bundle keeps exactly one `.bpmn` (the guard test stays green) and the Modeler "process application"
deploy never picks the demo file up:

```bash
cd _analysis
python timer_demo.py build --p7d PT45S --p23d PT45S --p2m PT45S   # default PT15S
python timer_demo.py deploy
python satellite_demo.py p13 --keep        # start process 13
#   Tasklist: complete "Draft the clinic letter" WITHIN the first timer - it writes letterComplete = false
python timer_demo.py restore               # ALWAYS restore when the demo is over
```

Then the chain fires by itself (measured with the 45 s timers):

```
16:01:56  start: P13_Activity_0az5u78 (draft) + P13_Event_1o398fk (Wait 7 days)   <- two parallel tokens
16:02:16  P13_Gateway_1vkb7lp -> P13_Activity_1xgw427 (review the clinical content)
16:02:42  P13_Event_1o398fk COMPLETED -> P13_Gateway_0r6f1k1 (letterComplete = false) -> record overdue letter
16:02:44  weekly Consultant reminders (auto) -> P13_Event_13dkl6t (Wait 23 days)
16:03:30  P13_Event_13dkl6t COMPLETED -> escalate to the Administrative Manager (auto)
16:04:15  P13_Event_0vf9q3w (Wait 2 months) COMPLETED -> escalate to senior management -> senior review
16:04:17  P13_Event_1ljjocr (the escalation branch ends)
```

Worth saying out loud:

- the draft task's output mapping writes `letterComplete = false`; if you have **not** completed it before the
  first timer fires, the gateway reads a missing variable and takes the default flow ("stop reminders") - so
  click the draft first (45 s is comfortably long, 15 s is not);
- the escalation branch ends while the letter branch is still open, so the instance stays `ACTIVE` on
  `P13_Activity_1xgw427`; complete the letter as well if you want the instance to finish;
- `python timer_demo.py status` lists the deployed versions; new instances always use the newest version,
  so **always finish with `restore`**.

### 12.3 The reassessment timer of phase 8 - no model change needed

`Timer_Reassessment` ("Wait until clinical reassessment date") takes its duration from a **variable**:
`= reassessmentDelay` (the form field, default `P7D`). Put a short ISO-8601 duration in the form and the
timer really fires:

```bash
cd _analysis
HOSPITAL_CLINICAL_DECISION=delay HOSPITAL_REASSESSMENT_DELAY=PT20S python walkthrough_driver.py --wait 115
```

```powershell
# PowerShell
$env:HOSPITAL_CLINICAL_DECISION="delay"; $env:HOSPITAL_REASSESSMENT_DELAY="PT20S"; python walkthrough_driver.py --wait 115
```

Measured: `R8_ClinicalReview COMPLETED 16:06:51 -> Gateway_ClinicalDecision 16:06:52 ->
Timer_Reassessment COMPLETED 16:07:13 (20.3 s) -> straight back into process 8 at 16:07:13`, then a second
cycle right after - i.e. the "delay care, wait for the reassessment date, review again" loop, live.

Not verified (impossible locally): Tasklist visibility under different identities/permissions (c8run runs
without authentication), and timer acceleration by moving the clock (the remote runtime mode cannot do it -
section 12.2 shortens the durations instead).

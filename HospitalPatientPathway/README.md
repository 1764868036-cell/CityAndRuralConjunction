# Hospital Patient Pathway: Camunda Forms

**English** | [简体中文](README.zh-CN.md)

This folder contains the integrated `1-17.bpmn` model and 16 deployment-bound Camunda Forms covering pathway stages 1-17. Start with the [submission index](submission/SUBMISSION_INDEX.md) for the project plan, acceptance tests, results, design decisions, PDFs and repository version.

**The model was repaired so that it actually deploys and can be automated** - see
[Model repairs](#model-repairs) below. **The Java job workers that run the automated steps live in
[`java/`](java/README.md).** The full change report (what was changed, why, and how it was verified)
is in [`WORK_REPORT.md`](WORK_REPORT.md) - 中文工作文档，逐条说明改动与验证证据。

## Use in Camunda Modeler

Open `1-17.bpmn` in Camunda Modeler and keep the `.form` files in this folder. The BPMN uses deployment binding, so deploy the BPMN and all referenced forms together. Each shared form uses `hospitalFormTask` to show only the section for the current user task. Form fields bind to same-name task-local variables, with explicit input and output mappings to and from process variables. Only the active task fields are propagated on submission. The mapping file lists every bound task and form.

## Shared forms

| Form file | Stage | User tasks |
| --- | --- | ---: |
| 01_receive_referral.form | 1 Receive Referral | 4 |
| 02_review_referral.form | 2 Review Referral | 2 |
| 03_initial_consultation.form | 3 Initial Consultation Booking | 4 |
| 04_diagnosis_consent.form | 4 Diagnosis and Consent | 6 |
| 05_treatment_services.form | 5 Arrange Treatment | 5 |
| 06_cost_allocation.form | 6 Verify Funding and Costs | 5 |
| 07_payment_result.form | 7 Process Payment | 8 |
| 08_continue_treatment.form | 8 Continue Treatment | 6 |
| 09_treatment_changes.form | 9 Treatment Changes | 5 |
| 10_appointment_outcomes.form | 10 Appointment Outcomes | 4 |
| 11_refund_decisions.form | 11 Refunds and Fund Transfers | 2 |
| 12_patient_enquiries.form | 12 Patient Enquiries | 7 |
| 13_clinic_letters.form | 13 Clinic Letters | 8 |
| 14_follow_up_appointments.form | 14 Follow-up Appointments | 3 |
| 16_identity_interruptions.form | 16 Identity and System Interruptions | 4 |
| 17_appointment_changes.form | 17 Appointment Changes | 8 |

All 81 user tasks in the collaboration have deployment-bound forms: 70 are in the executable hospital process and 11 are in external, non-executable participant pools. Process 15 report collection, generation and publication tasks are service tasks and do not use user forms.

The forms capture the information needed for the pathway while preserving clinical/administrative/financial boundaries. They do not ask for complete card details. Urgency rules remain a stakeholder decision; the enquiry form records the assessment without inventing a clinical urgency policy.

`Form_Task_Mapping.csv` is the task-to-form index, including the later appointment-change and other expanded task bindings.

## Job workers

`java/` contains the Spring Boot worker application for the executable hospital automation: 50 job
types covering 51 automated steps, plus the
simulated answers of the external parties (referring organisation, patient, scheduling service,
treatment/laboratory service, insurer, payment service provider).

The recorded runtime evidence used a local Camunda 8.9.19 cluster on ports 26500 / 8080. Start a compatible cluster before the commands below; see [deployment configuration](submission/DEPLOYMENT.md).

```bash
# Git Bash, from this folder

# 1. deploy the pathway - model and forms in ONE deployment, the forms are deployment-bound
curl -X POST http://localhost:8080/v2/deployments \
  $(for f in 1-17.bpmn *.form; do printf ' -F resources=@%s' "$f"; done)

# 2. start the workers
cd java && ./mvnw spring-boot:run
```

```powershell
# PowerShell, from this folder

# 1. deploy the pathway (curl.exe, not the Invoke-WebRequest alias)
$form = Get-ChildItem *.form | ForEach-Object { '-F'; "resources=@$($_.Name)" }
curl.exe -sS -X POST http://localhost:8080/v2/deployments -F "resources=@1-17.bpmn" @form

# 2. start the workers
Set-Location java; .\mvnw.cmd spring-boot:run
```

In Camunda Modeler you can open `1-17.bpmn` with the `.form` files next to it and press Deploy
instead of using the curl command. [`MANUAL_TEST_GUIDE.md`](MANUAL_TEST_GUIDE.md) walks the whole
pathway by hand: which step waits for which message, what to fill in Tasklist and which variables a
start message must carry (`MANUAL_TEST_GUIDE.zh-CN.md` is the Chinese version).

This folder is a Camunda Modeler **process application** (it contains the `.process-application`
marker), which is why a single Deploy sends the model *and* its 16 deployment-bound forms together.
The flip side: **every** `.bpmn` and `.form` below this folder rides along, so a stray copy breaks
the whole deployment.

| Message | Cause | Fix |
| --- | --- | --- |
| `cvc-complex-type.3.2.2: attribute 'name' is not allowed ... bpmn:group` | an unrepaired copy of the model sits somewhere below this folder. In this project that was `_analysis/original-1-14named.bpmn`, the untouched original of the delivered model | the original is stored as `_analysis/original-1-14named.bpmn.keep` (a non-deployable extension); `BpmnModelCoverageTest` fails when the tree holds more than one `.bpmn` |
| `Duplicated process id in resources 'a.bpmn' and 'b.bpmn'` | two models with the same process id in one deployment, e.g. a second copy of the model below this folder | keep exactly one `.bpmn` in the tree - same guard test |
| unexpected extra form versions | a stale copy of the forms (a build output in `java/target/`, for example) is deployed as a second version of the same form id | delete build output (`mvn clean`); the guard test fails when a form id appears twice in the tree |

The Problems panel also lists warnings about the element templates that ship with the Modeler (AWS
Bedrock, SQS, DynamoDB, ...). Those are not model errors and do not block a deployment.

`java/README.md` documents the job type to worker mapping, the message/correlation design, the
simulation switches and what was verified; `java/WORKER_MAP.md` lists every model element with its
worker.

## Model repairs (historical baseline)

This section records the earlier repair baseline. Later form, worker, layout and runtime changes are documented in the submission index and evidence. Current counts and deployment instructions are above.

The model as delivered could not be deployed at all, and twelve automated steps could not have been
picked up by any worker. Every change is minimal, documented here and replayable from the delivered
original with `_analysis/repair_model.py` (`_analysis/original-1-14named.bpmn.keep` is the untouched
input, `_analysis/repair-log.txt` the applied change list).

A second, separate script (`_analysis/repair_start_events.py`, log
`_analysis/repair-start-events-log.txt`) aligns the start events with the case study, so the pathway
is entered at process 1 (see row 7).

| # | Element(s) | Problem | Change |
| --- | --- | --- | --- |
| 1 | `P15_Group_Monitoring`, `P16_Group_AccessAndInterruptions` | `bpmn:group` has no `name` attribute in BPMN 2.0, so the whole model failed XSD validation (`cvc-complex-type.3.2.2`) | `name` attribute removed (the attribute is purely cosmetic) |
| 2 | `P13_Flow_ThreeMonth_Yes`, `P13_Flow_1p8oyk4` | an exclusive gateway branch without a condition and not marked as default flow; Zeebe rejects the deployment ("Must have a condition or be default flow") | the "still outstanding" branches got the condition `= letterComplete = false`, matching the 7-day checkpoint of the same process |
| 3 | `Message_P16_ServiceRestored` | referenced by the catch event `P16_Catch_Restored` but without a `zeebe:subscription`; Zeebe rejects it ("Must have exactly one zeebe:subscription extension element") | subscription with `correlationKey="= incidentReference"` added, so the restore message reaches the instance that reported the interruption |
| 4 | `P12_Event_09ty25j`, `P12_Event_0qw1zi1`, `P12_Event_11yxbm9` | end events carrying a `messageEventDefinition` without `messageRef` plus a `zeebe:taskDefinition` of type `end`/`normal` (transcription leftovers); a message throw needs a message, and a plain end event is not a job worker element | dangling `messageEventDefinition` and `taskDefinition` removed; the three end events stay the ends of the enquiry paths |
| 5 | `P15_Task_Collect`, `P15_Task_Generate`, `P15_Task_Publish`, `P16_Task_Audit`, `P16_Task_Reconcile` | service tasks without any `zeebe:taskDefinition`, so they could never create a job | job types `collect-pathway-data`, `generate-pathway-reports`, `publish-pathway-reports`, `record-access-decision`, `reconcile-offline-work` added |
| 6 | `P12_Activity_1cxqg1o`, `P12_Activity_0iv7m7o`, `P13_Activity_0wdxagy`, `P13_Activity_19r74id`, `P13_Activity_1t7mm3x`, `P13_Activity_0aygpgf`, `P13_Activity_0ccs70w` | send tasks whose job type was a placeholder (`send`, `pass information`) or the element id itself | real job types: `refer-clinical-enquiry`, `refer-finance-enquiry`, `send-letter-to-secretaries`, `distribute-approved-letter`, `return-letter-to-consultant`, `send-consultant-reminders`, `escalate-letter-to-manager` |
| 7 | `Start_Tasklist`, `P8_Start_CycleDue`, `Message_TasklistReferral` | the model had exactly one plain start event, `P8_Start_CycleDue` in process 8, so every "Start instance" (Operate, Tasklist, Modeler) entered **process 8** instead of the case study's process 1 - and then failed on the `= patientId` correlation key the instance did not have | `Start_Tasklist` ("Start referral in Tasklist", the entry of process 1, case study step 1.1) lost its message trigger and is now the model's plain start event; `P8_Start_CycleDue` got the message trigger `Next treatment cycle due` (case study step 8.1), like processes 9-16; the now unused `Tasklist referral entry` message was removed |

At that historical repair stage, the other model areas were not changed. The current submission includes later form, gateway, message and layout work documented separately.

## Files

| Path | Content |
| --- | --- |
| `1-17.bpmn` | merged pathway model (repaired, see above) |
| `*.form` | 16 deployment-bound Camunda Forms |
| `Form_Task_Mapping.csv` | task-to-form index |
| `java/` | Spring Boot job workers for the automated steps |
| `WORK_REPORT.md` | change report: model repairs, worker design, verification evidence (Chinese) |
| `MANUAL_TEST_GUIDE.md` | manual test walkthrough: entries, per-phase steps, branches, messages, troubleshooting |
| `MANUAL_TEST_GUIDE.zh-CN.md` | Chinese version of the manual test walkthrough |
| `README.zh-CN.md` | Chinese version of this README |
| `submission/` and `evidence/` | final plans, acceptance tests, design rationale, test results and curated evidence; local `_analysis/` is a development archive and is not part of the tagged repository |

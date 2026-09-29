# AISD: justification of design decisions

## 1. Process structure

The editable `1-17.bpmn` is one BPMN collaboration. The hospital participant (`Process_Hospital_Integrated`) is the executable process with one plain Tasklist start and one common end. Its 17 named pathway stages are arranged on a continuous diagram, with 11 hospital lanes. Eight external participants are separate collaboration pools. This makes the patient's route through referral, consultation, consent, treatment, finance and support visible while preserving the sender/receiver of messages. The stage PDF is a reading aid generated from the master model; the master BPMN is the source of truth.

**Alternative considered:** 17 independent executable processes connected by messages. That would give smaller diagrams, but would make a single case harder to follow and require additional correlation, versioning and recovery rules at each stage boundary. A single hospital process is suitable for the assessment's end-to-end route, although it produces a large canvas and needs stage views for review.

## 2. Participants, responsibilities and system boundary

Hospital lanes represent medical secretaries, consultants, specialist nurses, outpatient and treatment bookings, finance, pathway coordinators, call handlers, administrative management, IT support and senior management. Human clinical, identity, funding and exception decisions remain user tasks. Java workers perform deterministic automation such as requests, notifications, audit records and report generation.

The referring organisation, patient, scheduling service, treatment/laboratory/imaging service, insurer/funder, payment service provider, external correspondence service and delay-tracking system sit outside the executable hospital boundary. BPMN message flows show which party sends or receives data. Their pools are intentionally non-executable in the hospital deployment. Local simulation services stand in for those parties in test runs; they are not evidence of live integrations.

**Alternative considered:** put every participant into the executable hospital process. That would blur ownership and imply the hospital controls patients, insurers and payment infrastructure. Separate pools improve accountability, but local end-to-end tests need explicit simulated replies.

## 3. Task allocation and forms

All 81 collaboration user tasks have a deployment-bound editable form; 70 of these are in the executable hospital process. The 16 form files share stage-specific fields; `Form_Task_Mapping.csv` identifies task-to-form binding and the BPMN specifies input/output variable mappings. The user task is assigned to the lane responsible for the action. The worker map lists 50 job types for 51 executable automated steps. A human confirms decisions that need judgement; workers transport or record the outcome.

**Trade-off:** shared stage forms reduce duplication but need `hospitalFormTask` visibility and accurate mappings. The form audit and browser render/submit checks address this risk. Automation improves repeatability but cannot replace a clinician's judgement or an insurer's authorisation.

## 4. Gateways and business rules

Exclusive gateways use explicit FEEL conditions or a default route. Examples from the actual model include `documentsComplete = true`, `referralSuitable = true`, `informedConsent = true`, `allServicesAvailable = true`, `fundingAuthorised = true`, `paymentStatus = "paid"`, and the continuation decision `clinicalDecision = "continue" and fitToContinue = "yes"`. A stop decision takes priority over changing a plan. Formal changes require `requestStatus = "approved"` and `formalClinicalApproval = "yes"`; cost/funding impact triggers a further check.

Appointment changes use an identity gate, a slot within the requested review window and a two-week contact rule. The BPMN explicitly checks `identityVerified = "yes"`, compares the appointment date with `today()` and `requestedReviewBy`, and selects phone contact when the appointment is within 14 days. These conditions prevent an available but clinically late slot from silently passing.

**Alternative considered:** have workers decide every branch. Explicit gateway conditions are inspectable by a reviewer and remain close to the activity that produces the decision. Some expressions depend on human-provided data and external messages, so the acceptance plan includes boundary and missing-data cases.

## 5. Messages and external interactions

The model uses BPMN messages at participant boundaries. Correlation keys include `referralId`, `patientId` and `incidentReference` according to the event. The Java support code centralises message names and key construction. A hospital-specific message namespace isolates this model from historical definitions deployed in the same local cluster. The deployment and runtime records show actual local message correlation for sampled cases.

**Trade-off:** asynchronous messaging fits patient and service responses, but missing or incorrect keys can leave an instance waiting. The manual guide records the required key for each entry. Real adapters must add authentication, delivery guarantees, idempotency, failure monitoring and data minimisation.

## 6. Exceptions and recovery

The process includes missing referral documents, unsuitable referrals, unavailable slots/services, declined funding, failed or duplicate payment, refused consent, changed treatment, cancellations, no-shows, refund/transfer outcomes, pending correspondence, access denial and system downtime. Several routes use a human review or escalation. The correspondence branch has reminder intervals and an explicit completion path so a finished letter does not keep the case alive until a later timer. Downtime recovery uses an incident reference to correlate restoration and reconcile offline work.

The local runtime exercises the main route and selected alternatives. Seven-day, 23-day and two-month correspondence timers and concurrent letters have not all been observed in live time. They remain acceptance cases, not passed results.

## 7. Data, assumptions and limitations

Local tests use synthetic identifiers and an embedded H2 file database. No payment card details are collected by forms. Clinical urgency thresholds, real funding policies and production access rights are not supplied; the model records or routes those judgements without inventing a rule. The BPMN participant/lane design and acceptance criteria therefore need domain-owner sign-off before use with real patients.

The model is intentionally broad for coursework traceability. Its flat process and shared variables make concurrent, independent correspondence cases a risk. One potential production alternative is to isolate each letter in a multi-instance subprocess with a letter-specific key. That adds complexity and has not been implemented or validated here.

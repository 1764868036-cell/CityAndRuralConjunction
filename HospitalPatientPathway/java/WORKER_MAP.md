# Job type to worker map

Every automated step of `../1-17.bpmn` (the service tasks and send tasks of the executable
process `Process_Hospital_Integrated`) with the `@JobWorker` method that handles it, and the
tables of the embedded H2 system of record that step writes and reads.

How the columns are kept: the element, name, type, job type and worker columns are read from the
model and the sources, and `BpmnModelCoverageTest` fails when those two drift apart. The two
**records** columns are maintained by hand - they are not generated and not asserted cell by cell -
and they are spot-checked by `WorkerPersistenceTest`, which drives the demo path (and the repeats,
the report lookup and the audit trail) against an in-memory H2 and asserts per step which rows it
wrote and which values it answered from the record.

Model: `Process_Hospital_Integrated` - 51 automated steps, 50 workers.
Missing workers: 0. Orphaned workers: 0.

The **records** columns name the tables of `io.camunda.demo.hospital.persistence` the step
reaches through the `HospitalDatabase` facade: what it writes (`insert`, `update`, `append`)
and what it reads (`find...`, `count`, `existsById`). A dash means the step only produces
variables and publishes messages. The **Tables of the pathway** section below turns the same
mapping around: one row per table with the workers that write and read it.

This table is the authoritative index. [`WORKER_GUIDE.zh-CN.md`](WORKER_GUIDE.zh-CN.md) is the
explanatory companion (Chinese): variables read/written, records written/read, messages
published, retry loops and the configuration switches of the same workers.

| Model element | Element name | Element type | Job type | Worker | Records written | Records read |
| --- | --- | --- | --- | --- | --- | --- |
| `P14_Task_Book` | Book follow-up appointment | serviceTask | `book-follow-up` | `PatientContactWorkers#bookFollowUpAppointment` | appointment, treatment_service | appointment, treatment_service |
| `R7_CheckPriorCharge` | Check prior transaction and idempotency | serviceTask | `check-payment-idempotency` | `PaymentWorkers#checkPaymentIdempotency` | — | payment |
| `P15_Task_Collect` | Collect current pathway and exception data | serviceTask | `collect-pathway-data` | `ReportingWorkers#collectPathwayData` | — | referral, appointment, clinic_letter, enquiry, funding_authorisation, payment, refund_case, pathway_record, event_log |
| `P13_Activity_19r74id` | Distribute approved letter | sendTask | `distribute-approved-letter` | `ClinicLetterWorkers#distributeApprovedLetter` | clinic_letter | clinic_letter |
| `P13_Activity_0ccs70w` | Escalate to Administrative Manager | sendTask | `escalate-letter-to-manager` | `ClinicLetterWorkers#escalateLetterToManager` | — | — |
| `R7_FlagInvestigation` | Flag for Finance investigation; do not recharge | serviceTask | `flag-payment-investigation` | `PaymentWorkers#flagPaymentForInvestigation` | — | — |
| `R3_Task_ForwardAlternative` | Forward requested alternative date to appointment-change process | sendTask | `forward-appointment-change` | `ConsultationWorkers#forwardAppointmentChange` | — | — |
| `R3_Task_ForwardConflict` | Escalate urgent or out-of-window booking | sendTask | `forward-scheduling-conflict` | `ConsultationWorkers#forwardSchedulingConflict` | — | — |
| `P15_Task_Generate` | Generate pathway and operational reports | serviceTask | `generate-pathway-reports` | `ReportingWorkers#generatePathwayReports` | report | referral, slot_request, clinic_letter, enquiry, payment, refund_case, report |
| `P10_Task_NotifyReferrer` | Inform the referring organisation | sendTask | `inform-referring-organisation` | `PatientContactWorkers#informReferringOrganisation` | — | referral |
| `P11_Task_LinkRecords` | Record the link to the other booking | serviceTask | `link-fund-transfer-records` | `RefundWorkers#linkFundTransferRecords` | refund_case | refund_case |
| `P17_Task_NotifyPatient` | Send the updated appointment details to the patient | sendTask | `notify-changed-appointment` | `AppointmentChangeWorkers#notifyChangedAppointment` | appointment | appointment |
| `P14_Task_Notify` | Notify patient of appointment | sendTask | `notify-patient` | `PatientContactWorkers#notifyPatientAboutAppointment` | — | — |
| `P13_Service_OpenLetter` | Open clinic-letter record for this visit | serviceTask | `open-clinic-letter` | `ClinicLetterWorkers#openClinicLetter` | clinic_letter | patient, consultation, clinic_letter |
| `P15_Task_Publish` | Apply role-based access and publish reports securely | serviceTask | `publish-pathway-reports` | `ReportingWorkers#publishPathwayReports` | — | report |
| `R5_TreatmentBooked` | Services provisionally arranged | sendTask | `publish-provisional-treatment-booking` | `TreatmentWorkers#publishProvisionalTreatmentBooking` | treatment_cycle, treatment_service | treatment_service, treatment_cycle |
| `P16_Task_Reconcile` | Reconcile offline work and preserve its audit history | serviceTask | `reconcile-offline-work` | `IdentityAccessWorkers#reconcileOfflineWork` | — | access_audit, event_log |
| `P16_Task_Audit` | Record the access decision in the protected audit log | serviceTask | `record-access-decision` | `IdentityAccessWorkers#recordAccessDecision` | access_audit, event_log | — |
| `P15_Task_UpdateMonitoringRecord` | Update the monitoring dataset and audit trail | serviceTask | `record-pathway-update` | `ReportingWorkers#recordPathwayUpdate` | pathway_record, event_log | pathway_record |
| `R3_Waitlist` | Keep pending, notify patient and log retry | serviceTask | `record-pending-consultation` | `ConsultationWorkers#keepConsultationPending` | slot_request | slot_request |
| `R5_KeepPending` | Keep pending; notify team and log retry | serviceTask | `record-pending-treatment-service` | `TreatmentWorkers#keepTreatmentServicePending` | — | — |
| `P11_Task_RecordResult` | Record the outcome against the patient account | serviceTask | `record-refund-outcome` | `RefundWorkers#recordRefundOutcome` | — | refund_case |
| `P12_Activity_1cxqg1o` | Refer clinical enquiry to a qualified clinical team | sendTask | `refer-clinical-enquiry` | `EnquiryWorkers#referClinicalEnquiry` | enquiry | enquiry |
| `P12_Activity_0iv7m7o` | Refer payment or funding enquiry to Finance | sendTask | `refer-finance-enquiry` | `EnquiryWorkers#referFinanceEnquiry` | enquiry | enquiry |
| `P17_Task_ReferFundingChange` | Refer payment, funding, or exemption change to Finance | sendTask | `refer-funding-change` | `AppointmentChangeWorkers#referFundingChange` | — | — |
| `P17_Task_ReferPlanChange` | Refer treatment-plan change for clinical authorisation | sendTask | `refer-treatment-plan-change` | `AppointmentChangeWorkers#referTreatmentPlanChange` | — | — |
| `P8_Task_SendRefundCase` | Refer treatment stop to Finance for refund review | sendTask | `refer-treatment-stop-to-finance` | `ContinuationWorkers#referTreatmentStopToFinance` | — | — |
| `P8_Task_SendClinicalReply` | Return clinical response to Call Handling | sendTask | `reply-to-clinical-enquiry` | `ContinuationWorkers#replyToClinicalEnquiry` | enquiry | enquiry |
| `P6_Task_SendFinanceReply` | Return the Finance response to Call Handling | sendTask | `reply-to-finance-enquiry` | `FundingClearanceWorkers#replyToFinanceEnquiry` | enquiry | enquiry |
| `P6_Task_RequestPayment` | Request required advance patient payment | sendTask | `request-advance-payment` | `FundingClearanceWorkers#requestAdvancePayment` | payment | payment |
| `P17_Task_RequestSlot` | Request new appointment options from the scheduling service | sendTask | `request-appointment-change-options` | `AppointmentChangeWorkers#requestAppointmentChangeOptions` | appointment | appointment |
| `P17_Task_SendPriorityReview` | Request a Consultant priority decision | sendTask | `request-clinical-priority-review` | `AppointmentChangeWorkers#requestClinicalPriorityReview` | — | — |
| `R3_RequestSchedule` | Request available slot | sendTask | `request-consultation-slot` | `ConsultationWorkers#requestConsultationSlot` | treatment_service, slot_request | treatment_service, slot_request |
| `P14_Task_RequestSlot` | Request follow-up slot within the clinically required timeframe | sendTask | `request-follow-up-slot` | `PatientContactWorkers#requestFollowUpSlot` | — | clinic_letter |
| `R6_RequestAuth` | Request insurer or funder authorisation | sendTask | `request-funding-authorisation` | `FundingWorkers#requestFundingAuthorisation` | funding_authorisation | funding_authorisation, treatment_cycle |
| `P8_Task_SendNextCycle` | Send Consultant-authorised next-cycle request to Treatment Bookings | sendTask | `request-next-treatment-cycle` | `ContinuationWorkers#requestNextTreatmentCycle` | treatment_service | treatment_cycle, treatment_service |
| `R7_SendPaymentRequest` | Send secure payment request | sendTask | `request-payment` | `PaymentWorkers#requestPayment` | payment | payment |
| `R5_RequestService` | Request external treatment or service slot | sendTask | `request-treatment-service` | `TreatmentWorkers#requestTreatmentService` | consultation | appointment, consultation |
| `P8_Task_SendPriorityDecision` | Return the clinical priority decision to the pathway team | sendTask | `return-clinical-priority-decision` | `ContinuationWorkers#returnClinicalPriorityDecision` | — | — |
| `P13_Activity_1t7mm3x` | Return suspected error to Consultant | sendTask | `return-letter-to-consultant` | `ClinicLetterWorkers#returnLetterToConsultant` | clinic_letter | clinic_letter |
| `R3_BookSendOffer` | Book slot and send appointment details | sendTask | `send-appointment-confirmation` | `ConsultationWorkers#bookSlotAndSendOffer` | appointment, slot_request, treatment_service | appointment, slot_request, treatment_service |
| `P13_Task_SendToCorrespondence` | Send approved letter to the correspondence service | sendTask | `send-approved-letter-to-correspondence` | `ClinicLetterWorkers#sendApprovedLetterToCorrespondence` | clinic_letter | clinic_letter |
| `P13_Activity_0aygpgf` | Send weekly Consultant reminders | sendTask | `send-consultant-reminders` | `ClinicLetterWorkers#sendConsultantReminders` | — | — |
| `P6_Task_SendFundingClearance` | Record and send funding clearance | sendTask | `send-funding-clearance` | `FundingClearanceWorkers#sendFundingClearance` | funding_clearance, funding_authorisation | funding_authorisation, funding_clearance, treatment_cycle |
| `P13_Activity_0wdxagy` | Send approved letter to Medical Secretaries | sendTask | `send-letter-to-secretaries` | `ClinicLetterWorkers#sendLetterToSecretaries` | clinic_letter | clinic_letter |
| `P7_Task_SendPaymentConfirmation` | Send confirmed payment status to Treatment Bookings | sendTask | `send-payment-confirmation-to-bookings` | `PaymentWorkers#sendPaymentConfirmationToBookings` | — | payment, funding_clearance |
| `R1_RequestDocs` | Request missing documents | sendTask | `send-referral-documents-request` | `ReferralWorkers#requestMissingDocuments` | patient, referral | patient, referral |
| `R2_Decline` | Record reason and inform referrer | sendTask | `send-referral-outcome` | `ReferralWorkers#sendReviewOutcome` | patient, referral | patient, referral |
| `R2_SendAcceptedOutcome` | Send accepted referral outcome | sendTask | `send-referral-outcome` | `ReferralWorkers#sendReviewOutcome` | patient, referral | patient, referral |
| `P11_Task_SendRequest` | Send the refund request to the payment service provider | sendTask | `send-refund-request` | `RefundWorkers#sendRefundRequest` | refund_case | refund_case, payment |
| `P8_Task_SendPlanChange` | Send clinically authorised treatment-plan change | sendTask | `send-treatment-plan-change` | `ContinuationWorkers#sendTreatmentPlanChange` | — | — |

## Tables of the pathway

The 17 tables of `src/main/resources/db/schema.sql`, the step that writes each of them and
the step that reads it back. Both are job workers of the model: the workers own the system
of record, there is no second writer.

| Table | Written by | Read by |
| --- | --- | --- |
| `patient` | `send-referral-documents-request`, `send-referral-outcome` | `send-referral-documents-request`, `send-referral-outcome`, `open-clinic-letter` |
| `referral` | `send-referral-documents-request`, `send-referral-outcome` | `send-referral-documents-request`, `send-referral-outcome`, `inform-referring-organisation`, `collect-pathway-data`, `generate-pathway-reports` |
| `treatment_service` | `request-consultation-slot`, `send-appointment-confirmation`, `publish-provisional-treatment-booking`, `request-next-treatment-cycle`, `book-follow-up` | same five steps |
| `appointment` | `send-appointment-confirmation`, `book-follow-up`, `request-appointment-change-options`, `notify-changed-appointment` | `send-appointment-confirmation`, `request-treatment-service`, `book-follow-up`, `request-appointment-change-options`, `notify-changed-appointment`, `collect-pathway-data` |
| `slot_request` | `request-consultation-slot`, `send-appointment-confirmation`, `record-pending-consultation` | `request-consultation-slot`, `send-appointment-confirmation`, `record-pending-consultation`, `generate-pathway-reports` |
| `consultation` | `request-treatment-service` | `request-treatment-service`, `open-clinic-letter` |
| `treatment_cycle` | `publish-provisional-treatment-booking` | `publish-provisional-treatment-booking`, `request-funding-authorisation`, `send-funding-clearance`, `request-next-treatment-cycle` |
| `funding_authorisation` | `request-funding-authorisation`, `send-funding-clearance` | `request-funding-authorisation`, `send-funding-clearance`, `collect-pathway-data` |
| `funding_clearance` | `send-funding-clearance` | `send-funding-clearance`, `send-payment-confirmation-to-bookings` |
| `payment` | `request-advance-payment`, `request-payment` | `request-advance-payment`, `request-payment`, `check-payment-idempotency`, `send-payment-confirmation-to-bookings`, `send-refund-request`, `collect-pathway-data`, `generate-pathway-reports` |
| `refund_case` | `send-refund-request`, `link-fund-transfer-records` | `send-refund-request`, `record-refund-outcome`, `link-fund-transfer-records`, `collect-pathway-data`, `generate-pathway-reports` |
| `clinic_letter` | `open-clinic-letter`, `send-letter-to-secretaries`, `send-approved-letter-to-correspondence`, `distribute-approved-letter`, `return-letter-to-consultant` | `open-clinic-letter`, `send-letter-to-secretaries`, `send-approved-letter-to-correspondence`, `distribute-approved-letter`, `return-letter-to-consultant`, `request-follow-up-slot`, `collect-pathway-data`, `generate-pathway-reports` |
| `enquiry` | `reply-to-finance-enquiry`, `refer-clinical-enquiry`, `refer-finance-enquiry`, `reply-to-clinical-enquiry` | `reply-to-finance-enquiry`, `refer-clinical-enquiry`, `refer-finance-enquiry`, `reply-to-clinical-enquiry`, `collect-pathway-data`, `generate-pathway-reports` |
| `access_audit` | `record-access-decision` | `reconcile-offline-work` |
| `pathway_record` | `record-pathway-update` | `collect-pathway-data`, `record-pathway-update` |
| `report` | `generate-pathway-reports` | `generate-pathway-reports`, `publish-pathway-reports` |
| `event_log` | `record-access-decision`, `record-pathway-update` | `reconcile-offline-work`, `collect-pathway-data` |

`event_log` also records every external event the simulation sends
(`io.camunda.demo.hospital.simulation.ExternalEventService`); those rows are written outside
a job and are therefore not attributed to a step above.

The `treatment_service` row is the one table where a write is not a business fact of its own: the
model selects a service as free text (`selectedService`), and the five steps marked above resolve
it through `io.camunda.demo.hospital.support.ServiceCatalogue`, which **inserts the service on
first use** when the hospital's catalogue does not know it yet - so the slot request, the
appointment and every treatment cycle of a referral point at one catalogue row. `default_cycles`
is read back by `request-next-treatment-cycle` (how many passes the service normally needs).

## Messages

What the model waits for, and what is published into it:

| Fact | Number |
| --- | --- |
| Wait points in `Process_Hospital_Integrated` | 38 = 34 intermediate catch events + the 4 receive tasks of process 13 |
| Distinct message names waited for | 33 |
| Names published by a job worker | 15 (14 of them are waited for; `Receive referral outcome message` goes to the non-executable referrer pool) |
| Names no job worker publishes | 19 = the 13 entry events of `ExternalEventService` + the 4 messages the interval responder (`PatientAutoResponder`) repeats + 2 that nobody publishes |

The 19 names a worker does not publish are `Incoming referral package`, `Clinic visit completed`,
`Finance enquiry received message`, `Next treatment cycle due`, `Patient attends a treatment cycle
message`, `TreatmentChangeRequestMessage`, `AppointmentCancellationReportMessage`,
`PatientQuestionMessage`, `Monitoring run or report request`, `A pathway record changes message`,
`Staff identity or access change request`, `System or external-service interruption`,
`Patient requests a change to an existing appointment message`, `Patient appointment preference`,
`Patient consents message`, `TestResultsMessage`, `System or external service restored`,
`Patient refuses treatment message` and `Clinic letter completed`.

The last two are waitable but are published by nothing at all, and both are harmless by design:
`R4_Catch_Refusal` is the second branch of the event-based gateway `R4_Gateway_WaitPatientDecision`;
a refusal arrives as the consent message carrying `informedConsent = false`, and the exclusive
gateway `R4_Consent` behind the catches routes it to the refusal path (its "Yes" flow reads
`informedConsent = true`, the "No" flow is the default), so the refusal message itself is never
needed. `Clinic letter completed` is waited for by the three completion timers of process 13
(`P13_Catch_CompletedSevenDay` / `P13_Catch_CompletedOneMonth` / `P13_Catch_CompletedThreeMonth`),
each of which expires into its deadline path if the message never arrives, so a letter that is not
completed in time escalates instead of blocking.

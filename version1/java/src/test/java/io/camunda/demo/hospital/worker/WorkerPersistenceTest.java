package io.camunda.demo.hospital.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Report;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves that the workers keep the pathway in the system of record, without a cluster.
 *
 * <p>The test drives the workers of the demo-critical path in the order the model drives them -
 * referral intake and outcome, appointment booking and change, consultation and consent, treatment
 * service and cycle, funding authorisation and clearance, payment, refund, clinic letter,
 * monitoring and report - and asserts after every stage what the record holds. That makes the
 * test the executable form of two promises: every table has a worker that writes it, and every
 * reader answers from the record and not only from the variables it was activated with.
 *
 * <p>No greenfield database is used: the context replays the shipped {@code schema.sql} and
 * {@code data.sql} into an in-memory H2 (see {@code src/test/resources/application.properties}),
 * so the seeded patient and referral are the subject of the pathway and a test run never touches
 * the developer's file database. The messenger is a mock: publishing a BPMN message needs a
 * cluster, and none of these assertions is about the messages.
 */
@SpringBootTest
@DisplayName("The workers of the pathway and their H2 records")
class WorkerPersistenceTest {

    private static final String REFERRAL_ID = "REF-1001";
    private static final String PATIENT_ID = "PAT-1001";
    private static final long PROCESS_INSTANCE_KEY = 2251799813685249L;

    /** The service the referral selects; the seeded catalogue does not have it. */
    private static final String SELECTED_SERVICE = "Oncology day unit";
    private static final String REGISTERED_SERVICE = "SVC-ONCOLOGY-DAY-UNIT";

    /** The tables the persistence layer owns; every one of them must be written by a worker. */
    private static final List<String> TABLES = List.of(
            "patient", "referral", "treatment_service", "appointment", "slot_request", "consultation",
            "treatment_cycle", "funding_authorisation", "funding_clearance", "payment", "refund_case",
            "clinic_letter", "enquiry", "access_audit", "pathway_record", "report", "event_log");

    @Autowired
    private HospitalDatabase database;

    @Autowired
    private ReferralWorkers referralWorkers;
    @Autowired
    private ConsultationWorkers consultationWorkers;
    @Autowired
    private TreatmentWorkers treatmentWorkers;
    @Autowired
    private FundingWorkers fundingWorkers;
    @Autowired
    private FundingClearanceWorkers fundingClearanceWorkers;
    @Autowired
    private PaymentWorkers paymentWorkers;
    @Autowired
    private RefundWorkers refundWorkers;
    @Autowired
    private ClinicLetterWorkers clinicLetterWorkers;
    @Autowired
    private EnquiryWorkers enquiryWorkers;
    @Autowired
    private ContinuationWorkers continuationWorkers;
    @Autowired
    private PatientContactWorkers patientContactWorkers;
    @Autowired
    private IdentityAccessWorkers identityAccessWorkers;
    @Autowired
    private ReportingWorkers reportingWorkers;
    @Autowired
    private AppointmentChangeWorkers appointmentChangeWorkers;

    /** The workers publish BPMN messages; those are verified against the model, not here. */
    @MockitoBean
    private ExternalPartyMessenger messenger;

    @Autowired
    private DataSource dataSource;

    private final AtomicLong jobKeys = new AtomicLong(900000000000000000L);

    @Test
    @DisplayName("the demo-critical path is written to the record step by step")
    void demoCriticalPathIsPersisted() {
        JdbcTemplate meta = new JdbcTemplate(dataSource);

        // --- referral intake and outcome ------------------------------------------------
        Map<String, Object> referral = vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "patientContact", "alex.morgan@example.org / 07700 900123",
                "referralReceivedDate", "2026-01-04", "referrerName", "Riverside General Practice",
                "referralReason", "Suspected cardiac arrhythmia", "missingDocuments", "blood results");
        referralWorkers.requestMissingDocuments(
                job("send-referral-documents-request", "R1_RequestDocs", referral));

        assertThat(database.referrals().findById(REFERRAL_ID))
                .as("the intake step records the referral")
                .hasValueSatisfying(recorded -> assertThat(recorded.status()).isEqualTo("received"));
        assertThat(database.patients().findById(PATIENT_ID))
                .as("the intake step records the details the referral package carries")
                .hasValueSatisfying(patient -> {
                    assertThat(patient.contactEmail()).isEqualTo("alex.morgan@example.org");
                    assertThat(patient.contactPhone()).isEqualTo("07700 900123");
                    assertThat(patient.familyName()).isEqualTo("Morgan");
                    assertThat(patient.givenName()).isEqualTo("Alex");
                });

        referralWorkers.sendReviewOutcome(job("send-referral-outcome", "R2_SendAcceptedOutcome", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "referralSuitable", true, "referralPriority", "urgent", "selectedService", SELECTED_SERVICE,
                "clinicianId", "CLIN-77", "referralDecisionReason", "Clinically suitable")));

        assertThat(database.referrals().findById(REFERRAL_ID))
                .as("the review outcome is recorded on the same referral")
                .hasValueSatisfying(recorded -> {
                    assertThat(recorded.status()).isEqualTo("accepted");
                    assertThat(recorded.priority()).isEqualTo("urgent");
                });

        // --- appointment booking --------------------------------------------------------
        Map<String, Object> scheduling = vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "selectedService", SELECTED_SERVICE, "clinicianId", "CLIN-77", "consultationRetryCount", 0);
        consultationWorkers.requestConsultationSlot(
                job("request-consultation-slot", "R3_RequestSchedule", scheduling));

        assertThat(database.treatmentServices().findById(REGISTERED_SERVICE))
                .as("the service the referral selected becomes a catalogue entry the bookings point at")
                .hasValueSatisfying(service -> {
                    assertThat(service.serviceName()).isEqualTo(SELECTED_SERVICE);
                    assertThat(service.active()).isTrue();
                });
        assertThat(database.slotRequests().findOpenByReferralId(REFERRAL_ID))
                .as("the slot search is opened with the service and the first attempt")
                .hasValueSatisfying(request -> {
                    assertThat(request.serviceCode()).isEqualTo(REGISTERED_SERVICE);
                    assertThat(request.attemptCount()).isEqualTo(1);
                    assertThat(request.status()).isEqualTo("open");
                });

        consultationWorkers.bookSlotAndSendOffer(job("send-appointment-confirmation", "R3_BookSendOffer", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "selectedService", SELECTED_SERVICE, "clinicianId", "CLIN-77",
                "date_of_appointment", "2026-02-10", "appointmentTime", "10:30")));

        assertThat(database.appointments().findById("APPT-" + REFERRAL_ID))
                .as("the offered slot is recorded with the patient decision the pathway simulated")
                .hasValueSatisfying(appointment -> {
                    assertThat(appointment.serviceCode()).isEqualTo(REGISTERED_SERVICE);
                    assertThat(appointment.status()).isEqualTo("booked");
                    assertThat(appointment.patientDecision()).isEqualTo("accept");
                    assertThat(appointment.scheduledFor()).isEqualTo(LocalDate.of(2026, 2, 10).atTime(10, 30));
                });
        assertThat(database.slotRequests().findOpenByReferralId(REFERRAL_ID))
                .as("booking the slot resolves the slot search")
                .isEmpty();

        // --- consultation and consent, treatment service and cycle ----------------------
        treatmentWorkers.requestTreatmentService(job("request-treatment-service", "R5_RequestService", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "selectedService", SELECTED_SERVICE, "clinicianId", "CLIN-77", "assessmentDate", "2026-02-11",
                "treatmentPlanReference", "TP-1001", "diagnosis", "Suspected cardiac arrhythmia",
                "informedConsent", true, "consentDate", "2026-02-11", "consentRecordedBy", "CLIN-77",
                "treatmentServiceRetryCount", 1)));

        assertThat(database.consultations().findById("CONS-" + REFERRAL_ID))
                .as("phase 5 records the consultation and the consent the treatment rests on")
                .hasValueSatisfying(consultation -> {
                    assertThat(consultation.consentStatus()).isEqualTo("consented");
                    assertThat(consultation.appointmentId()).as("read back from the appointment record")
                            .isEqualTo("APPT-" + REFERRAL_ID);
                    assertThat(consultation.recommendation()).isEqualTo("TP-1001");
                });

        treatmentWorkers.publishProvisionalTreatmentBooking(
                job("publish-provisional-treatment-booking", "R5_TreatmentBooked", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                        "selectedService", SELECTED_SERVICE, "cycleNumber", 1,
                        "provisionalTreatmentDate", "2026-02-20", "finalTreatmentDate", "2026-03-06")));

        assertThat(database.treatmentCycles().findByReferralServiceAndNumber(REFERRAL_ID, REGISTERED_SERVICE, 1))
                .as("the provisionally arranged services become the first treatment cycle")
                .hasValueSatisfying(cycle -> {
                    assertThat(cycle.status()).isEqualTo("planned");
                    assertThat(cycle.plannedStart()).isEqualTo(LocalDate.of(2026, 2, 20));
                    assertThat(cycle.plannedEnd()).isEqualTo(LocalDate.of(2026, 3, 6));
                });

        // --- funding authorisation and clearance ----------------------------------------
        fundingWorkers.requestFundingAuthorisation(
                job("request-funding-authorisation", "R6_RequestAuth", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "payerName", "Demo Insurer",
                        "estimatedCost", "1200.00", "approvedFundingAmount", "1200.00", "currency", "GBP",
                        "funderDecisionReason", "Authorised (simulated)")));

        assertThat(database.fundingAuthorisations().findById("FUND-" + REFERRAL_ID))
                .as("the funding request and the funder's answer are one row")
                .hasValueSatisfying(authorisation -> {
                    assertThat(authorisation.status()).isEqualTo("authorised");
                    assertThat(authorisation.authorisedAmount()).isEqualByComparingTo("1200.00");
                    assertThat(authorisation.treatmentCycleId()).as("read back from the cycle record")
                            .isNotBlank();
                });

        Map<String, Object> clearance = fundingClearanceWorkers.sendFundingClearance(
                job("send-funding-clearance", "P6_Task_SendFundingClearance", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "approvedFundingAmount", "1200.00",
                        "currency", "GBP", "clearanceReference", "CLR-REF-1001")));

        assertThat(database.fundingClearances().findById("CLR-" + REFERRAL_ID))
                .as("the clearance releases money against the authorisation that is on record")
                .hasValueSatisfying(released -> {
                    assertThat(released.authorisationId()).isEqualTo("FUND-" + REFERRAL_ID);
                    assertThat(released.clearedAmount()).isEqualByComparingTo("1200.00");
                    assertThat(released.status()).isEqualTo("cleared");
                });
        assertThat(clearance.get("fundingClearanceAmount")).as("the answer carries the released amount")
                .isEqualTo("1200.00");

        // --- payment --------------------------------------------------------------------
        fundingClearanceWorkers.requestAdvancePayment(
                job("request-advance-payment", "P6_Task_RequestPayment", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "chargeAmount", "120.00",
                        "currency", "GBP")));

        assertThat(database.payments().findById("PAY-" + REFERRAL_ID))
                .as("the advance payment opens the one charge of the referral")
                .hasValueSatisfying(charge -> {
                    assertThat(charge.status()).isEqualTo("requested");
                    assertThat(charge.amount()).isEqualByComparingTo("120.00");
                    assertThat(charge.chargeReference()).isEqualTo("ADV-" + REFERRAL_ID);
                });

        paymentWorkers.requestPayment(job("request-payment", "R7_SendPaymentRequest", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "chargeAmount", "120.00", "currency", "GBP",
                "invoiceReference", "INV-1001")));

        assertThat(database.payments().findByReferralId(REFERRAL_ID))
                .as("process 7 completes the charge the advance step opened - one charge, one lifecycle")
                .singleElement()
                .satisfies(charge -> {
                    assertThat(charge.paymentId()).isEqualTo("PAY-" + REFERRAL_ID);
                    assertThat(charge.status()).isEqualTo("paid");
                    assertThat(charge.chargeReference()).isEqualTo("INV-1001");
                    assertThat(charge.settledOn()).isNotNull();
                });

        Map<String, Object> idempotency = paymentWorkers.checkPaymentIdempotency(
                job("check-payment-idempotency", "R7_CheckPriorCharge", vars(
                        "referralId", REFERRAL_ID, "paymentRetryCount", 1)));

        assertThat(idempotency.get("priorChargeFound"))
                .as("the idempotency check answers from the charge that collected money")
                .isEqualTo(true);
        assertThat(idempotency.get("priorChargeReference")).isEqualTo("PAY-" + REFERRAL_ID);

        Map<String, Object> confirmation = paymentWorkers.sendPaymentConfirmationToBookings(
                job("send-payment-confirmation-to-bookings", "P7_Task_SendPaymentConfirmation", vars(
                        "referralId", REFERRAL_ID, "paymentReference", "PAY-" + REFERRAL_ID)));

        assertThat(confirmation.get("confirmedPaymentReference")).as("confirmed from the charge on record")
                .isEqualTo("PAY-" + REFERRAL_ID);
        assertThat(confirmation.get("confirmedClearedAmount")).isEqualTo("1200.00");

        // --- continuation review and enquiries ------------------------------------------
        Map<String, Object> nextCycle = continuationWorkers.requestNextTreatmentCycle(
                job("request-next-treatment-cycle", "P8_Task_SendNextCycle", vars(
                        "referralId", REFERRAL_ID, "selectedService", SELECTED_SERVICE,
                        "fitToContinue", true, "clinicalDecision", "continue")));

        assertThat(nextCycle.get("nextCycleNumber"))
                .as("the next pass is the one after the cycles on record")
                .isEqualTo(2);
        assertThat(nextCycle.get("serviceDefaultCycles"))
                .as("how many passes the service needs comes from the catalogue")
                .isEqualTo(database.treatmentServices().findById(REGISTERED_SERVICE).orElseThrow().defaultCycles());
        assertThat(database.treatmentCycles().findByReferralServiceAndNumber(REFERRAL_ID, REGISTERED_SERVICE, 1))
                .as("the review the model repeats is recorded on the pass it was about")
                .hasValueSatisfying(cycle -> {
                    assertThat(cycle.testResultsFitToContinue()).isTrue();
                    assertThat(cycle.reviewOutcome()).isEqualTo("continue");
                });

        enquiryWorkers.referClinicalEnquiry(job("refer-clinical-enquiry", "P12_Activity_1cxqg1o", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "contactChannel", "telephone", "enquirySummary", "Is the next cycle safe?")));

        assertThat(database.enquiries().findById("ENQ-CLIN-" + REFERRAL_ID))
                .as("the clinical enquiry is opened for the team that answers it")
                .hasValueSatisfying(enquiry -> assertThat(enquiry.status()).isEqualTo("open"));

        continuationWorkers.replyToClinicalEnquiry(
                job("reply-to-clinical-enquiry", "P8_Task_SendClinicalReply", vars(
                        "referralId", REFERRAL_ID, "clinicalEnquiryResponse", "The team confirms the plan")));

        assertThat(database.enquiries().findById("ENQ-CLIN-" + REFERRAL_ID))
                .as("the clinical answer closes the enquiry on record")
                .hasValueSatisfying(enquiry -> {
                    assertThat(enquiry.status()).isEqualTo("answered");
                    assertThat(enquiry.response()).isEqualTo("The team confirms the plan");
                    assertThat(enquiry.answeredOn()).isNotNull();
                });

        enquiryWorkers.referFinanceEnquiry(job("refer-finance-enquiry", "P12_Activity_0iv7m7o", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "contactChannel", "telephone", "financeEnquirySummary", "What is left to pay?")));
        fundingClearanceWorkers.replyToFinanceEnquiry(
                job("reply-to-finance-enquiry", "P6_Task_SendFinanceReply", vars(
                        "referralId", REFERRAL_ID, "financeEnquiryResponse", "120.00 GBP, already collected")));

        assertThat(database.enquiries().findById("ENQ-FIN-" + REFERRAL_ID))
                .as("the finance answer closes the finance enquiry on record")
                .hasValueSatisfying(enquiry -> {
                    assertThat(enquiry.status()).isEqualTo("answered");
                    assertThat(enquiry.response()).isEqualTo("120.00 GBP, already collected");
                });

        // --- the referring organisation is informed from the record ---------------------
        Map<String, Object> referrer = patientContactWorkers.informReferringOrganisation(
                job("inform-referring-organisation", "P10_Task_NotifyReferrer", vars(
                        "referralId", REFERRAL_ID, "caseEventType", "cancellation")));

        assertThat(String.valueOf(referrer.get("referrerNotification")))
                .as("the organisation that is informed is the one the referral record names")
                .contains("Riverside General Practice");

        // --- clinic letter --------------------------------------------------------------
        clinicLetterWorkers.openClinicLetter(job("open-clinic-letter", "P13_Service_OpenLetter", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "letterStartDate", "2026-03-06")));

        assertThat(database.clinicLetters().findById("LET-" + PATIENT_ID))
                .as("the letter names the patient and the consultation it documents")
                .hasValueSatisfying(letter -> {
                    assertThat(letter.status()).isEqualTo("draft");
                    assertThat(letter.consultationId()).isEqualTo("CONS-" + REFERRAL_ID);
                    assertThat(letter.subject()).as("spelled as the patient record spells it")
                            .isEqualTo("Clinic letter for Alex Morgan");
                });

        clinicLetterWorkers.sendApprovedLetterToCorrespondence(
                job("send-approved-letter-to-correspondence", "P13_Task_SendToCorrespondence", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan")));
        assertThat(database.clinicLetters().findById("LET-" + PATIENT_ID))
                .hasValueSatisfying(letter -> assertThat(letter.status()).isEqualTo("sent"));

        clinicLetterWorkers.distributeApprovedLetter(
                job("distribute-approved-letter", "P13_Activity_19r74id", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan")));
        assertThat(database.clinicLetters().findById("LET-" + PATIENT_ID))
                .as("a distributed letter is complete and carries the date it left the hospital")
                .hasValueSatisfying(letter -> {
                    assertThat(letter.status()).isEqualTo("complete");
                    assertThat(letter.sentOn()).isNotNull();
                });

        // --- follow-up appointment and the appointment change ---------------------------
        Map<String, Object> followUp = patientContactWorkers.requestFollowUpSlot(
                job("request-follow-up-slot", "P14_Task_RequestSlot", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "patient_name", "Alex Morgan")));

        assertThat(followUp.get("followUpLetterReference"))
                .as("the follow-up starts from the clinic letter that is on record")
                .isEqualTo("LET-" + PATIENT_ID);

        patientContactWorkers.bookFollowUpAppointment(job("book-follow-up", "P14_Task_Book", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "selectedService", SELECTED_SERVICE,
                "requestedReviewBy", "2026-04-03")));

        assertThat(database.appointments().findById("APPT-FU-" + REFERRAL_ID))
                .as("the follow-up is booked as the next appointment of the referral")
                .hasValueSatisfying(appointment -> {
                    assertThat(appointment.status()).isEqualTo("booked");
                    assertThat(appointment.scheduledFor()).isEqualTo(LocalDate.of(2026, 4, 3).atStartOfDay());
                });

        appointmentChangeWorkers.requestAppointmentChangeOptions(
                job("request-appointment-change-options", "P17_Task_RequestSlot", vars(
                        "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "selectedService", SELECTED_SERVICE)));
        assertThat(database.appointments().findById("APPT-" + REFERRAL_ID))
                .as("asking for new options marks the appointment that has to move")
                .hasValueSatisfying(appointment -> assertThat(appointment.status()).isEqualTo("change-requested"));

        Map<String, Object> changed = appointmentChangeWorkers.notifyChangedAppointment(
                job("notify-changed-appointment", "P17_Task_NotifyPatient", vars(
                        "referralId", REFERRAL_ID, "date_of_appointment", "2026-03-20", "appointmentTime", "11:00")));

        assertThat(database.appointments().findById("APPT-" + REFERRAL_ID))
                .as("the notification moves the appointment on record")
                .hasValueSatisfying(appointment -> {
                    assertThat(appointment.status()).isEqualTo("booked");
                    assertThat(appointment.scheduledFor()).isEqualTo(LocalDate.of(2026, 3, 20).atTime(11, 0));
                });
        assertThat(changed.get("changedAppointmentDate")).as("announced from the record, not the option")
                .isEqualTo("2026-03-20");

        // --- refund ---------------------------------------------------------------------
        refundWorkers.sendRefundRequest(job("send-refund-request", "P11_Task_SendRequest", vars(
                "referralId", REFERRAL_ID, "approvedRefundAmount", "120.00", "currency", "GBP")));

        assertThat(database.refundCases().findById("RFD-" + REFERRAL_ID + "-refund"))
                .as("the reversal names the charge it reverses")
                .hasValueSatisfying(refundCase -> {
                    assertThat(refundCase.paymentId()).isEqualTo("PAY-" + REFERRAL_ID);
                    assertThat(refundCase.status()).isEqualTo("closed");
                    assertThat(refundCase.amount()).isEqualByComparingTo("120.00");
                });

        Map<String, Object> refundOutcome = refundWorkers.recordRefundOutcome(
                job("record-refund-outcome", "P11_Task_RecordResult", vars(
                        "referralId", REFERRAL_ID, "refundStatus", "completed", "refundedAmount", "0.00")));

        assertThat(refundOutcome.get("refundRecordedAmount"))
                .as("the patient account is booked with the amount the case on record holds")
                .isEqualTo("120.00");

        refundWorkers.linkFundTransferRecords(job("link-fund-transfer-records", "P11_Task_LinkRecords", vars(
                "referralId", REFERRAL_ID, "transferBookingReference", "BKG-OTHER")));
        assertThat(database.refundCases().findById("RFD-" + REFERRAL_ID + "-refund"))
                .hasValueSatisfying(refundCase -> assertThat(refundCase.linkedBookingReference()).isEqualTo("BKG-OTHER"));

        // --- monitoring and report ------------------------------------------------------
        Map<String, Object> dataSet = reportingWorkers.collectPathwayData(
                job("collect-pathway-data", "P15_Task_Collect", Map.of()));

        reportingWorkers.generatePathwayReports(job("generate-pathway-reports", "P15_Task_Generate", vars(
                "reportingPeriod", "2026-03-01 to 2026-03-31", "requestedBy", "Performance and quality team")));

        assertThat(database.reports().findByReportType("RPT-REFERRALS"))
                .as("one report row per report of the run")
                .singleElement()
                .satisfies(report -> {
                    assertThat(report.rowCount()).isEqualTo((int) database.referrals().count());
                    assertThat(report.reportScope()).isEqualTo("2026-03-01 to 2026-03-31");
                });

        Map<String, Object> published = reportingWorkers.publishPathwayReports(
                job("publish-pathway-reports", "P15_Task_Publish", Map.of()));
        assertThat(String.valueOf(published.get("publishedReportReferences")))
                .as("publishing lists the reports that are on record")
                .contains("RPT-REFERRALS-" + LocalDate.now())
                .contains("RPT-REFUNDS-" + LocalDate.now());

        reportingWorkers.recordPathwayUpdate(job("record-pathway-update", "P15_Task_UpdateMonitoringRecord", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "changedRecord", "referral",
                "changeSummary", "A pathway record changed (simulated)")));

        assertThat(database.pathwayRecords().findById("PR-" + REFERRAL_ID))
                .as("the monitoring update is the monitoring record of the referral")
                .hasValueSatisfying(record -> {
                    assertThat(record.processInstanceKey()).isEqualTo(PROCESS_INSTANCE_KEY);
                    assertThat(record.monitoringFlag()).isTrue();
                });

        Map<String, Object> refreshed = reportingWorkers.collectPathwayData(
                job("collect-pathway-data", "P15_Task_Collect", Map.of()));
        assertThat((Integer) refreshed.get("dataSetRecordCount"))
                .as("the data set counts what the record holds, so it grows with every record a worker writes")
                .isGreaterThan((Integer) dataSet.get("dataSetRecordCount"));

        // --- identity, access and the audit trail ---------------------------------------
        identityAccessWorkers.recordAccessDecision(job("record-access-decision", "P16_Task_Audit", vars(
                "referralId", REFERRAL_ID, "patientId", PATIENT_ID, "targetStaffUserId", "STAFF-2291",
                "requestType", "role change", "approvedRole", "clinic administrator",
                "accessAuthorised", true, "authorisingAdministrator", "PRIVACY-OFFICER")));

        assertThat(database.accessAudits().findByStaffUserId("STAFF-2291"))
                .as("the access decision is appended to the audit")
                .singleElement()
                .satisfies(audit -> assertThat(audit.decision()).isEqualTo("approved"));
        assertThat(database.eventLog().findByReferralId(REFERRAL_ID))
                .as("the audit step also appends to the audit trail of the pathway")
                .isNotEmpty();

        Map<String, Object> reconciled = identityAccessWorkers.reconcileOfflineWork(
                job("reconcile-offline-work", "P16_Task_Reconcile", vars(
                        "referralId", REFERRAL_ID, "incidentReference", "INC-1001")));

        assertThat((Integer) reconciled.get("reconciledOfflineRecords"))
                .as("the reconciliation counts the trail it preserves")
                .isGreaterThanOrEqualTo(1);
        assertThat(reconciled.get("auditHistoryPreserved")).isEqualTo(true);

        // --- every table of the schema was written by a worker --------------------------
        Map<String, Integer> empty = new LinkedHashMap<>();
        for (String table : TABLES) {
            Integer rows = meta.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            if (rows == null || rows == 0) {
                empty.put(table, rows);
            }
        }
        assertThat(empty)
                .as("the workers of the demo path write every table the persistence layer owns")
                .isEmpty();
    }

    @Test
    @DisplayName("a second instance of the same referral re-opens its slot search instead of colliding")
    void aSecondInstanceReopensTheSlotSearchOfTheSameReferral() {
        String referralId = "REF-T8-SLOT";
        givenReferral(referralId);
        Map<String, Object> scheduling = vars(
                "referralId", referralId, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "selectedService", SELECTED_SERVICE, "clinicianId", "CLIN-77",
                "date_of_appointment", "2026-05-04", "appointmentTime", "09:00");

        consultationWorkers.requestConsultationSlot(
                job("request-consultation-slot", "R3_RequestSchedule", scheduling));
        consultationWorkers.bookSlotAndSendOffer(
                job("send-appointment-confirmation", "R3_BookSendOffer", scheduling));
        assertThat(database.slotRequests().findOpenByReferralId(referralId))
                .as("the first pass resolved the slot search")
                .isEmpty();

        // The referral is worked a second time: the same row is re-opened. A status-filtered
        // decision would miss the booked row here and fail on the primary key.
        consultationWorkers.requestConsultationSlot(
                job("request-consultation-slot", "R3_RequestSchedule", scheduling));

        assertThat(countRows("slot_request", "slot_request_id", "SLOT-" + referralId))
                .as("the repeat reuses the referral's slot request instead of inserting a second row")
                .isEqualTo(1);
        assertThat(database.slotRequests().findOpenByReferralId(referralId))
                .as("the repeat left the request open for the new search")
                .hasValueSatisfying(request -> {
                    assertThat(request.status()).isEqualTo("open");
                    assertThat(request.resolvedAppointmentId()).isNull();
                });
    }

    @Test
    @DisplayName("a second instance of the same referral updates its refund case instead of colliding")
    void aSecondInstanceUpdatesTheRefundCaseOfTheSameReferral() {
        String referralId = "REF-T8-REFUND";
        givenReferral(referralId);
        fundingClearanceWorkers.requestAdvancePayment(job("request-advance-payment", "P6_Task_RequestPayment",
                vars("referralId", referralId, "patientId", PATIENT_ID, "chargeAmount", "95.00", "currency", "GBP")));
        paymentWorkers.requestPayment(job("request-payment", "R7_SendPaymentRequest",
                vars("referralId", referralId, "patientId", PATIENT_ID, "chargeAmount", "95.00", "currency", "GBP")));

        Map<String, Object> reversal = vars(
                "referralId", referralId, "approvedRefundAmount", "95.00", "currency", "GBP");
        refundWorkers.sendRefundRequest(job("send-refund-request", "P11_Task_SendRequest", reversal));
        // The provider answers again, because the referral is on its second pass through process 11.
        refundWorkers.sendRefundRequest(job("send-refund-request", "P11_Task_SendRequest", reversal));

        assertThat(countRows("refund_case", "refund_case_id", "RFD-" + referralId + "-refund"))
                .as("the repeat reuses the referral's case instead of inserting a second row")
                .isEqualTo(1);
        assertThat(database.refundCases().findById("RFD-" + referralId + "-refund"))
                .as("the case that the first pass closed carries the result of the repeat")
                .hasValueSatisfying(refundCase -> {
                    assertThat(refundCase.paymentId()).isEqualTo("PAY-" + referralId);
                    assertThat(refundCase.status()).isEqualTo("closed");
                    assertThat(refundCase.outcome()).isEqualTo("completed");
                });
    }

    @Test
    @DisplayName("a second instance of the same referral keeps the answered enquiry")
    void aSecondInstanceKeepsTheAnswerOfTheEnquiry() {
        String referralId = "REF-T8-ENQUIRY";
        givenReferral(referralId);
        Map<String, Object> question = vars(
                "referralId", referralId, "patientId", PATIENT_ID, "patient_name", "Alex Morgan",
                "contactChannel", "telephone", "enquirySummary", "Will the treatment be repeated?");

        enquiryWorkers.referClinicalEnquiry(job("refer-clinical-enquiry", "P12_Activity_1cxqg1o", question));
        continuationWorkers.replyToClinicalEnquiry(job("reply-to-clinical-enquiry", "P8_Task_SendClinicalReply",
                vars("referralId", referralId, "clinicalEnquiryResponse", "Yes, if the results allow it")));

        // The second pass runs both steps again: the question is recorded again and the clinical
        // team answers the enquiry a second time, which must not replace the answer on record.
        enquiryWorkers.referClinicalEnquiry(job("refer-clinical-enquiry", "P12_Activity_1cxqg1o", question));
        continuationWorkers.replyToClinicalEnquiry(job("reply-to-clinical-enquiry", "P8_Task_SendClinicalReply",
                vars("referralId", referralId, "clinicalEnquiryResponse", "A second, later answer")));

        assertThat(countRows("enquiry", "enquiry_id", "ENQ-CLIN-" + referralId))
                .as("the repeat reuses the referral's enquiry instead of inserting a second row")
                .isEqualTo(1);
        assertThat(database.enquiries().findById("ENQ-CLIN-" + referralId))
                .as("a question that was answered is not turned back into an open one and keeps its answer")
                .hasValueSatisfying(enquiry -> {
                    assertThat(enquiry.status()).isEqualTo("answered");
                    assertThat(enquiry.response()).isEqualTo("Yes, if the results allow it");
                    assertThat(enquiry.answeredOn()).isNotNull();
                });
    }

    @Test
    @DisplayName("publishing lists the newest report of a type even when an earlier day is on record")
    void publishingUsesTheNewestReportEvenWhenAnEarlierDayIsOnRecord() {
        database.reports().insert(new Report("RPT-PAYMENTS-2026-01-01", "RPT-PAYMENTS", "2026-01",
                "performance and quality team", LocalDateTime.of(2026, 1, 1, 8, 0), 1, "generated", "older day"));

        reportingWorkers.generatePathwayReports(job("generate-pathway-reports", "P15_Task_Generate", vars(
                "reportingPeriod", "2026-04-01 to 2026-04-30", "requestedBy", "Performance and quality team")));

        Map<String, Object> published = reportingWorkers.publishPathwayReports(
                job("publish-pathway-reports", "P15_Task_Publish", Map.of()));

        assertThat(String.valueOf(published.get("publishedReportReferences")))
                .as("two reports of one type must not end the publishing step in an incident")
                .contains("RPT-PAYMENTS-" + LocalDate.now())
                .doesNotContain("RPT-PAYMENTS-2026-01-01");
    }

    @Test
    @DisplayName("no worker reaches past the persistence layer")
    void noWorkerReachesPastThePersistenceLayer() {
        Path workers = Path.of("src", "main", "java", "io", "camunda", "demo", "hospital", "worker");
        assertThat(workers).as("the worker sources are read from the project, not from the classpath").isDirectory();

        Map<String, String> offenders = new LinkedHashMap<>();
        int scanned = 0;
        try (Stream<Path> sources = Files.list(workers)) {
            for (Path source : sources.filter(file -> file.getFileName().toString().endsWith(".java")).toList()) {
                scanned++;
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String forbidden : List.of("java.sql", "javax.sql", "JdbcClient", "JdbcTemplate",
                        "JdbcOperations", "DataSource", "SimpleJdbc")) {
                    if (text.contains(forbidden)) {
                        offenders.put(source.getFileName().toString(), forbidden);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the worker sources of " + workers, e);
        }

        assertThat(scanned).as("the scan has to see every worker class of the package").isGreaterThanOrEqualTo(14);
        assertThat(offenders)
                .as("the workers reach the system of record through HospitalDatabase only; a JDBC type in this "
                        + "package would let a worker build its own statement instead of using the records")
                .isEmpty();
    }

    /** Creates the referral the repeat tests work on, without touching the demo referral. */
    private void givenReferral(String referralId) {
        referralWorkers.requestMissingDocuments(job("send-referral-documents-request", "R1_RequestDocs",
                vars("referralId", referralId, "patientId", PATIENT_ID, "patient_name", "Alex Morgan")));
    }

    /** Number of rows of one table with this key, read without a repository. */
    private int countRows(String table, String keyColumn, String key) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + keyColumn + " = ?", Integer.class, key);
    }

    /** An activated job as every worker sees it: the job key, the element and the variables. */
    private ActivatedJob job(String jobType, String elementId, Map<String, Object> variables) {
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(jobKeys.incrementAndGet());
        when(job.getType()).thenReturn(jobType);
        when(job.getElementId()).thenReturn(elementId);
        when(job.getProcessInstanceKey()).thenReturn(PROCESS_INSTANCE_KEY);
        when(job.getVariablesAsMap()).thenReturn(variables);
        when(job.getVariables()).thenReturn(String.valueOf(variables));
        return job;
    }

    private Map<String, Object> vars(Object... namesAndValues) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            variables.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return variables;
    }
}

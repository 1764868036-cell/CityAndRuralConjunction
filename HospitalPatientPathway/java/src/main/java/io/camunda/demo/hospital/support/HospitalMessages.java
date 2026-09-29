package io.camunda.demo.hospital.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Message names and correlation keys of the integrated pathway model
 * ({@code 1-17.bpmn}, process {@code Process_Hospital_Integrated}).
 *
 * <p>The names are the {@code bpmn:message name} values of the model; Camunda correlates a
 * published message on that name (not on the XML id) plus the correlation key defined in
 * {@code zeebe:subscription correlationKey}.
 *
 * <p>Almost every message belongs to one referral and correlates on {@code referralId}. The
 * clinic-letter and laboratory messages correlate on {@code patientId}, the letter-completion
 * timers on {@code clinicLetterId} and the "service restored" message on {@code incidentReference}.
 *
 * <p>The integrated model has exactly one plain start event ({@code Start_Tasklist}); every other
 * entry point is an intermediate catch event behind the external-trigger router (the event-based
 * gateway {@code Gateway_ExternalTriggerRouter}, "Wait for referral or other external message"),
 * one {@code Inbox_*} element per segment, so no message of this model is a "start message" - all
 * are published with a time to live and buffered until the pathway waits for them.
 */
public final class HospitalMessages {

    private HospitalMessages() {
    }

    /** Correlation key variable used by the referral-level messages. */
    public static final String REFERRAL_ID = "referralId";

    /** Correlation key variable used by the clinic-letter and laboratory messages. */
    public static final String PATIENT_ID = "patientId";

    /** Correlation key variable used by the clinic-letter completion timers. */
    public static final String CLINIC_LETTER_ID = "clinicLetterId";

    /** Correlation key variable used by the "service restored" message of process 16. */
    public static final String INCIDENT_REFERENCE = "incidentReference";

    /** BPMN process id of the integrated hospital pathway. */
    public static final String PATHWAY_PROCESS_ID = "Process_Hospital_Integrated";

    // --- entry events (fed into the pathway through the external-trigger router) ------------

    /** Starts the referral journey: the incoming referral package (process 1, R1_Start). */
    public static final String INCOMING_REFERRAL = "Hospital pathway: Incoming referral package";

    /** Process 4 entry: the clinic visit is completed (Inbox_R4_Start). */
    public static final String CLINIC_VISIT_COMPLETED = "Hospital pathway: Clinic visit completed";

    /** Process 6 entry: a finance enquiry reaches Call Handling (Inbox_P6_Start_FinanceInquiry). */
    public static final String FINANCE_ENQUIRY_RECEIVED = "Hospital pathway: Finance enquiry received message";

    /** Process 8 entry: the next treatment cycle becomes due (Inbox_P8_Start_CycleDue). */
    public static final String TREATMENT_CYCLE_DUE = "Hospital pathway: Next treatment cycle due";

    /** Process 8 entry: the patient attends a treatment cycle (Inbox_P8_Start_PatientArrives). */
    public static final String PATIENT_CYCLE_ATTENDANCE = "Hospital pathway: Patient attends a treatment cycle message";

    /** Process 9 entry: a request to change the treatment timetable arrives (Inbox_P9_Start_ChangeRequest). */
    public static final String TREATMENT_CHANGE_REQUEST = "Hospital pathway: TreatmentChangeRequestMessage";

    /** Process 10 entry: cancellation, refusal or no-show reported (Inbox_P10_Start_Report). */
    public static final String APPOINTMENT_CANCELLATION_REPORT = "Hospital pathway: AppointmentCancellationReportMessage";

    /** Process 12 entry: the patient asks a question (Inbox_P12_StartEvent_1). */
    public static final String PATIENT_QUESTION = "Hospital pathway: PatientQuestionMessage";

    /** Process 15 entry: a monitoring run or report request arrives (Inbox_P15_Start_Monitor). */
    public static final String MONITORING_REQUEST = "Hospital pathway: Monitoring run or report request";

    /** Process 15 entry: a pathway record changes (Inbox_P15_Start_RecordUpdate). */
    public static final String PATHWAY_RECORD_UPDATED = "Hospital pathway: A pathway record changes message";

    /** Process 16 entry: a staff identity or access change is requested (Inbox_P16_Start_Access). */
    public static final String ACCESS_REQUEST = "Hospital pathway: Staff identity or access change request";

    /** Process 16 entry: a system or external-service interruption is reported (Inbox_P16_Start_Interruption). */
    public static final String INTERRUPTION_REPORTED = "Hospital pathway: System or external-service interruption";

    /** Process 17 entry: the patient requests a change to an existing appointment (Inbox_P17_Start_ChangeRequest). */
    public static final String APPOINTMENT_CHANGE_REQUEST = "Hospital pathway: Patient requests a change to an existing appointment message";

    // --- messages requested by the hospital and answered by an external party ----------------

    /** Hospital asks the referring organisation for missing documents (R1_ReceiveDocs). */
    public static final String REQUESTED_REFERRAL_DOCUMENTS = "Hospital pathway: Requested referral documents";

    /** Outcome of the referral review, sent back to the referring organisation. */
    public static final String REFERRAL_OUTCOME = "Hospital pathway: Receive referral outcome message";

    /** Patient sends appointment preferences (process 3, R3_PatientPreference). */
    public static final String PATIENT_PREFERENCE = "Hospital pathway: Patient appointment preference";

    /** Scheduling service answers with appointment options (processes 3, 14 and 17). */
    public static final String SCHEDULING_AVAILABILITY = "Hospital pathway: Receive appointment options message";

    /** Patient accepts the offered appointment (R3_PatientResponse). */
    public static final String PATIENT_APPOINTMENT_RESPONSE = "Hospital pathway: Patient appointment response";

    /** Patient proposes another date instead (R3_AlternativeDate). */
    public static final String PATIENT_ALTERNATIVE_DATE = "Hospital pathway: Alternative consultation date";

    /** Patient declines the consultation (R3_PatientDeclined). */
    public static final String PATIENT_DECLINED = "Hospital pathway: Patient declines consultation";

    /** Patient consent decision for the proposed treatment (R4_Catch_Consent). */
    public static final String PATIENT_CONSENT_DECISION = "Hospital pathway: Patient consents message";

    /** Patient refuses the proposed treatment (R4_Catch_Refusal). */
    public static final String PATIENT_REFUSAL_DECISION = "Hospital pathway: Patient refuses treatment message";

    /** External treatment / laboratory / imaging service answers the slot request (R5_ReceiveService). */
    public static final String TREATMENT_SERVICE_AVAILABILITY = "Hospital pathway: Treatment or external service availability";

    /** Insurer or funding organisation answers the authorisation request (R6_ReceiveAuth). */
    public static final String FUNDER_DECISION = "Hospital pathway: Funding authorisation decision";

    /** Payment service provider reports the payment result (R7_ReceivePaymentResult). */
    public static final String PAYMENT_RESULT = "Hospital pathway: Payment result from provider";

    /** Payment service provider reports the refund result (P11_Catch_RefundResult). */
    public static final String REFUND_RESULT = "Hospital pathway: Receive PSP refund result message";

    /** Laboratory results for the next treatment cycle (P8_Catch_Results). */
    public static final String TEST_RESULTS = "Hospital pathway: TestResultsMessage";

    /** The correspondence service returns the letter entry for release (P13_Catch_CorrespondenceEntry). */
    public static final String LETTER_ENTRY = "Hospital pathway: Receive letter entry for release message";

    /** The interrupted system or external service is available again (P16_Catch_Restored). */
    public static final String SERVICE_RESTORED = "Hospital pathway: System or external service restored";

    /** The clinic letter is completed, caught by the 7/23-day and 2-month timers (process 13). */
    public static final String CLINIC_LETTER_COMPLETED = "Hospital pathway: Clinic letter completed";

    // --- clinic letter (process 13) ----------------------------------------------------------

    /** Consultant hands the letter to the Medical Secretaries. */
    public static final String LETTER_FROM_DOCTOR = "Hospital pathway: LetterFromDoctor";

    /** Medical Secretaries return the letter after an administrative correction. */
    public static final String LETTER_BACK_FOR_RECHECKING = "Hospital pathway: LetterBackForRechecking";

    /** Consultant answers a reminder about an outstanding letter. */
    public static final String MESSAGE_FROM_DOCTOR = "Hospital pathway: MessageFromDoctor";

    /** Administrative Manager reports back after an escalation. */
    public static final String REPORT_FROM_DOCTOR = "Hospital pathway: ReportFromDoctor";

    // --- catalogue ---------------------------------------------------------------------------

    private static final Map<String, String> CORRELATION_KEY_VARIABLES = correlationKeyVariables();

    private static Map<String, String> correlationKeyVariables() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(INCOMING_REFERRAL, REFERRAL_ID);
        keys.put(CLINIC_VISIT_COMPLETED, REFERRAL_ID);
        keys.put(FINANCE_ENQUIRY_RECEIVED, REFERRAL_ID);
        keys.put(TREATMENT_CYCLE_DUE, REFERRAL_ID);
        keys.put(PATIENT_CYCLE_ATTENDANCE, REFERRAL_ID);
        keys.put(TREATMENT_CHANGE_REQUEST, REFERRAL_ID);
        keys.put(APPOINTMENT_CANCELLATION_REPORT, REFERRAL_ID);
        keys.put(PATIENT_QUESTION, REFERRAL_ID);
        keys.put(MONITORING_REQUEST, REFERRAL_ID);
        keys.put(PATHWAY_RECORD_UPDATED, REFERRAL_ID);
        keys.put(ACCESS_REQUEST, REFERRAL_ID);
        keys.put(INTERRUPTION_REPORTED, REFERRAL_ID);
        keys.put(APPOINTMENT_CHANGE_REQUEST, REFERRAL_ID);
        keys.put(REQUESTED_REFERRAL_DOCUMENTS, REFERRAL_ID);
        keys.put(REFERRAL_OUTCOME, REFERRAL_ID);
        keys.put(PATIENT_PREFERENCE, REFERRAL_ID);
        keys.put(SCHEDULING_AVAILABILITY, REFERRAL_ID);
        keys.put(PATIENT_APPOINTMENT_RESPONSE, REFERRAL_ID);
        keys.put(PATIENT_ALTERNATIVE_DATE, REFERRAL_ID);
        keys.put(PATIENT_DECLINED, REFERRAL_ID);
        keys.put(PATIENT_CONSENT_DECISION, REFERRAL_ID);
        keys.put(PATIENT_REFUSAL_DECISION, REFERRAL_ID);
        keys.put(TREATMENT_SERVICE_AVAILABILITY, REFERRAL_ID);
        keys.put(FUNDER_DECISION, REFERRAL_ID);
        keys.put(PAYMENT_RESULT, REFERRAL_ID);
        keys.put(REFUND_RESULT, REFERRAL_ID);
        keys.put(LETTER_ENTRY, REFERRAL_ID);
        keys.put(CLINIC_LETTER_COMPLETED, CLINIC_LETTER_ID);
        keys.put(TEST_RESULTS, PATIENT_ID);
        keys.put(LETTER_FROM_DOCTOR, PATIENT_ID);
        keys.put(LETTER_BACK_FOR_RECHECKING, PATIENT_ID);
        keys.put(MESSAGE_FROM_DOCTOR, PATIENT_ID);
        keys.put(REPORT_FROM_DOCTOR, PATIENT_ID);
        keys.put(SERVICE_RESTORED, INCIDENT_REFERENCE);
        return Collections.unmodifiableMap(keys);
    }

    /**
     * The integrated model starts at its plain start event only, so no message is a start message;
     * every message is published with a time to live and buffered until the pathway waits for it.
     */
    private static final Set<String> START_MESSAGES = Set.of();

    /** {@code true} when the message starts a process instance. */
    public static boolean isStartMessage(String messageName) {
        return START_MESSAGES.contains(messageName);
    }

    /**
     * Name of the process variable the model uses as correlation key for this message,
     * or {@code null} when the subscription defines no correlation key.
     */
    public static String correlationKeyVariable(String messageName) {
        return CORRELATION_KEY_VARIABLES.get(messageName);
    }

    /** Every message of the integrated model that has a subscription and can be published. */
    public static Set<String> allMessageNames() {
        return CORRELATION_KEY_VARIABLES.keySet();
    }

    /** {@code true} when the model waits for this message somewhere. */
    public static boolean isKnown(String messageName) {
        return CORRELATION_KEY_VARIABLES.containsKey(messageName);
    }
}

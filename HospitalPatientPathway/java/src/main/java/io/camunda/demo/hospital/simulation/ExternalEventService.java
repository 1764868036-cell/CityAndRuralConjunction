package io.camunda.demo.hospital.simulation;

import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.PathwayAudit;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The world outside the hospital: everything the pathway receives from a person or an external
 * organisation that no hospital step can produce on its own.
 *
 * <p>Two kinds of messages live here:
 *
 * <ul>
 *   <li><b>entry events</b> - they are fed into the external-trigger router (the event-based
 *       gateway {@code Gateway_ExternalTriggerRouter}, "Wait for referral or other external
 *       message") and start one pathway segment. Every entry point of the integrated model is an
 *       inbox element of that router ({@code Inbox_*}), which hands the message on to the segment
 *       it belongs to (the referral package, a clinic visit, a finance enquiry, the next treatment
 *       cycle, a treatment-change request, a cancellation report, a patient question, a monitoring
 *       run, a pathway-record change, an access request, an interruption report or an
 *       appointment-change request).</li>
 *   <li><b>unsolicited answers</b> - the patient sends preferences or a consent decision, the
 *       laboratory sends results, an external service reports that it is available again.</li>
 * </ul>
 *
 * <p>Segments without a router message are entered from inside the pathway (process 11 refunds from
 * a treatment stop, process 13 letters from consent, process 14 follow-up from a distributed
 * letter), so they have no method here. Send that the hospital requests itself (documents, a slot,
 * an authorisation, a payment or a refund) is answered by the job worker of the requesting step.
 *
 * <p>Every message this service sends is also written to the audit trail of the pathway: a
 * message that arrived from outside is the one thing nothing else in the process can prove
 * afterwards.
 */
@Component
public class ExternalEventService {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalEventService.class);

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final PathwayAudit audit;

    public ExternalEventService(ExternalPartyMessenger messenger, SimulationProperties properties,
            PathwayAudit audit) {
        this.messenger = messenger;
        this.properties = properties;
        this.audit = audit;
    }

    // --- entry events (fed into the external-trigger router) -------------------------------

    /** A referring organisation submits a referral package (starts process 1). */
    public void submitReferral(String referralId, String patientName) {
        Map<String, Object> referral = referralVariables(referralId, patientName);
        referral.put("referrerName", "Riverside General Practice");
        referral.put("patientContact", "alex.morgan@example.org / 07700 900123");
        referral.put("referralReceivedDate", LocalDate.now().toString());
        referral.put("referralReason", "Investigation of a persistent symptom before treatment is planned");
        referral.put("documentReferences", "DOC-REF-1 (referral letter); DOC-REF-2 (blood results)");
        publish(HospitalMessages.INCOMING_REFERRAL, referral);
    }

    /** The hospital takes a referral into the system itself: the pathway starts at its plain start event. */
    public void startReferralFromTasklist(String referralId, String patientName) {
        messenger.startInstance(HospitalMessages.PATHWAY_PROCESS_ID, referralVariables(referralId, patientName));
    }

    /** The clinic visit is completed and the consent discussion starts (process 4, Inbox_R4_Start). */
    public void completeClinicVisit(String referralId, String patientName) {
        Map<String, Object> visit = referralVariables(referralId, patientName);
        visit.put("appointmentDate", LocalDate.now().toString());
        visit.put("visitSummary", "Consultation completed; diagnosis and consent discussion due (simulated)");
        publish(HospitalMessages.CLINIC_VISIT_COMPLETED, visit);
    }

    /** A finance enquiry reaches Call Handling (process 6, Inbox_P6_Start_FinanceInquiry). */
    public void reportFinanceEnquiry(String referralId, String patientName) {
        Map<String, Object> enquiry = referralVariables(referralId, patientName);
        enquiry.put("enquiryReceivedAt", LocalDate.now().toString());
        enquiry.put("enquiryCategory", "finance");
        enquiry.put("enquirySummary", "Patient asks what the treatment will cost and how it is funded");
        publish(HospitalMessages.FINANCE_ENQUIRY_RECEIVED, enquiry);
    }

    /** The next treatment cycle of an admitted patient becomes due (process 8, Inbox_P8_Start_CycleDue). */
    public void notifyTreatmentCycleDue(String referralId, String patientName) {
        Map<String, Object> cycle = referralVariables(referralId, patientName);
        cycle.put("cycleNumber", 2);
        publish(HospitalMessages.TREATMENT_CYCLE_DUE, cycle);
    }

    /** The patient attends a treatment cycle (process 8, Inbox_P8_Start_PatientArrives). */
    public void reportPatientAttendsCycle(String referralId, String patientName) {
        Map<String, Object> attendance = referralVariables(referralId, patientName);
        attendance.put("attendanceDate", LocalDate.now().toString());
        attendance.put("attendanceSummary", "Patient attended the treatment cycle (simulated)");
        publish(HospitalMessages.PATIENT_CYCLE_ATTENDANCE, attendance);
    }

    /** The patient asks for a different treatment plan (process 9, Inbox_P9_Start_ChangeRequest). */
    public void requestTreatmentChange(String referralId, String patientName) {
        Map<String, Object> change = referralVariables(referralId, patientName);
        change.put("requestChannel", "telephone");
        change.put("requestReceivedDate", LocalDate.now().toString());
        change.put("requestSummary", "Patient would like the next cycle moved to a later date");
        publish(HospitalMessages.TREATMENT_CHANGE_REQUEST, change);
    }

    /** The patient cancels the appointment or does not attend (process 10, Inbox_P10_Start_Report). */
    public void reportCancellation(String referralId, String patientName) {
        Map<String, Object> report = referralVariables(referralId, patientName);
        report.put("caseEventType", "cancellation");
        report.put("eventDate", LocalDate.now().toString());
        report.put("eventReason", "Patient cancelled the appointment (simulated)");
        publish(HospitalMessages.APPOINTMENT_CANCELLATION_REPORT, report);
    }

    /** The patient asks a question (process 12, Inbox_P12_StartEvent_1). */
    public void askPatientQuestion(String referralId, String patientName) {
        Map<String, Object> enquiry = referralVariables(referralId, patientName);
        enquiry.put("enquiryReceivedAt", LocalDate.now().toString());
        enquiry.put("contactChannel", "telephone");
        enquiry.put("enquirySummary", "Patient asks when the appointment will be arranged and what it costs");
        publish(HospitalMessages.PATIENT_QUESTION, enquiry);
    }

    /** A monitoring run or report request arrives (process 15, Inbox_P15_Start_Monitor). */
    public void requestMonitoringRun(String referralId) {
        Map<String, Object> request = referralVariables(referralId, null);
        request.put("requestedBy", "Performance and quality team");
        request.put("reportingPeriod", LocalDate.now().withDayOfMonth(1).toString() + " to " + LocalDate.now());
        publish(HospitalMessages.MONITORING_REQUEST, request);
    }

    /** A pathway record changes (process 15, Inbox_P15_Start_RecordUpdate). */
    public void reportPathwayRecordUpdate(String referralId) {
        Map<String, Object> update = referralVariables(referralId, null);
        update.put("changedRecord", "referral");
        update.put("changeSummary", "A pathway record changed and the monitoring dataset has to be updated (simulated)");
        publish(HospitalMessages.PATHWAY_RECORD_UPDATED, update);
    }

    /** A staff identity or access change is requested (process 16, Inbox_P16_Start_Access). */
    public void requestAccessChange(String referralId) {
        Map<String, Object> request = referralVariables(referralId, null);
        request.put("targetStaffUserId", "STAFF-2291");
        request.put("targetStaffName", "R. Okafor");
        request.put("requestType", "role change");
        request.put("requestedRole", "clinic administrator");
        request.put("verificationMethod", "line manager confirmation");
        publish(HospitalMessages.ACCESS_REQUEST, request);
    }

    /** A system or external-service interruption is reported (process 16, Inbox_P16_Start_Interruption). */
    public void reportSystemInterruption(String referralId) {
        Map<String, Object> report = referralVariables(referralId, null);
        report.put("incidentReference", properties.getDefaultIncidentReference());
        report.put("affectedService", "pathway worklist");
        report.put("interruptionStartDate", LocalDate.now().toString());
        report.put("impactSummary", "Worklist unavailable; staff record work on the downtime form (simulated)");
        publish(HospitalMessages.INTERRUPTION_REPORTED, report);
    }

    /** The patient requests a change to an existing appointment (process 17, Inbox_P17_Start_ChangeRequest). */
    public void requestAppointmentChange(String referralId, String patientName) {
        Map<String, Object> request = referralVariables(referralId, patientName);
        request.put("requestChannel", "telephone");
        request.put("requestReceivedDate", LocalDate.now().toString());
        request.put("appointmentChangeType", "date");
        request.put("requestSummary", "Patient asks to move the appointment to a different day (simulated)");
        publish(HospitalMessages.APPOINTMENT_CHANGE_REQUEST, request);
    }

    // --- unsolicited answers ------------------------------------------------------------

    /** The patient sends the appointment preferences that phase 3 waits for. */
    public void sendPatientPreference(String referralId) {
        Map<String, Object> preference = new LinkedHashMap<>();
        preference.put("preferredClinician", "no preference");
        preference.put("preferredDate", LocalDate.now().plusDays(14).toString());
        preference.put("contactChannel", "telephone");
        publish(HospitalMessages.PATIENT_PREFERENCE, preference, referralId);
    }

    /** The patient answers the consent question that phase 4 waits for. */
    public void sendPatientConsentDecision(String referralId) {
        boolean consented = !"refuse".equalsIgnoreCase(properties.getPatientConsentDecision());
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("informedConsent", consented);
        decision.put("consentDecision", consented ? "consented" : "refused");
        decision.put("consentDecisionDate", LocalDate.now().toString());
        decision.put("consentNotes", "Patient consent decision (simulated)");
        publish(HospitalMessages.PATIENT_CONSENT_DECISION, decision, referralId);
    }

    /** The patient proposes another consultation date. */
    public void sendAlternativeDate(String referralId) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("alternativeAppointmentDate", LocalDate.now().plusDays(21).toString());
        publish(HospitalMessages.PATIENT_ALTERNATIVE_DATE, answer, referralId);
    }

    /** The patient declines the consultation. */
    public void declineConsultation(String referralId) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("patientDeclineReason", "Patient declined the offered appointment (simulated)");
        publish(HospitalMessages.PATIENT_DECLINED, answer, referralId);
    }

    /** The laboratory sends the blood test results of the next treatment cycle. */
    public void sendTestResults(String patientId) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("patientId", patientId);
        results.put("fitToContinue", properties.isTestResultsFitToContinue() ? "yes" : "no");
        results.put("clinicalDecisionReason", "Blood test results (simulated)");
        results.put("bloodTestSummary", "Values within the range required for the next cycle (simulated)");
        publish(HospitalMessages.TEST_RESULTS, results, patientId);
    }

    /** The interrupted system or external service is available again. */
    public void reportServiceRestored(String incidentReference) {
        String reference = incidentReference == null ? properties.getDefaultIncidentReference() : incidentReference;
        Map<String, Object> restored = new LinkedHashMap<>();
        restored.put("incidentReference", reference);
        restored.put("restoredAt", LocalDate.now().toString());
        restored.put("restorationSummary", "Pathway worklist available again (simulated)");
        publish(HospitalMessages.SERVICE_RESTORED, restored, reference);
    }

    // --- plumbing -----------------------------------------------------------------------

    private Map<String, Object> referralVariables(String referralId, String patientName) {
        String id = referralId == null ? properties.getDefaultReferralId() : referralId;
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(HospitalMessages.REFERRAL_ID, id);
        variables.put("patient_name", patientName == null ? properties.getDefaultPatientName() : patientName);
        variables.put(HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
        return variables;
    }

    /**
     * Publishes a referral-correlated message; the correlation key is read from the variables.
     *
     * <p>The message leaves before it is recorded, and the recording is guarded here as well: the
     * trail is evidence about the pathway, never a gate in it, so neither a subject the log cannot
     * name nor a trail that is unavailable may stop the event the pathway waits for.
     */
    private void publish(String messageName, Map<String, Object> variables) {
        LOG.info("simulating external event '{}'", messageName);
        messenger.publish(messageName, variables, variables);
        recordInTrail(messageName, variables);
    }

    /** Publishes a message whose correlation key is given explicitly (not the referral id). */
    private void publish(String messageName, Map<String, Object> payload, String correlationValue) {
        Map<String, Object> correlationVariables = new LinkedHashMap<>(payload);
        String keyVariable = HospitalMessages.correlationKeyVariable(messageName);
        if (correlationValue != null && keyVariable != null) {
            correlationVariables.put(keyVariable, correlationValue);
        }
        LOG.info("simulating external event '{}' ({}={})", messageName, keyVariable, correlationValue);
        messenger.publish(messageName, correlationVariables, payload);
        recordInTrail(messageName, correlationVariables);
    }

    /** Writes one corridor message down; a trail that fails is reported and never handed on. */
    private void recordInTrail(String messageName, Map<String, Object> variables) {
        try {
            audit.messageReceived(messageName, variables);
        } catch (RuntimeException e) {
            LOG.warn("could not record the corridor message '{}' in the audit trail: {}", messageName, e.getMessage());
        }
    }
}

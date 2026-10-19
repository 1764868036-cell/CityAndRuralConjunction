package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.ClinicLetter;
import io.camunda.demo.hospital.persistence.model.Consultation;
import io.camunda.demo.hospital.persistence.model.Patient;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Process 13 "Clinic Letters": every automatic step of the letter workflow, from handing the
 * approved letter to the correspondence service up to the escalation at three months.
 *
 * <p>The people involved (Medical Secretaries, Consultant, Administrative Manager) are
 * represented by the four messages of phase 13 - the workers publish the message their step
 * hands over to, and the matching receive task continues the pathway.
 *
 * <ul>
 *   <li>{@code open-clinic-letter} (P13_Service_OpenLetter) opens the letter record and sets the
 *       {@code clinicLetterId} the completion timers correlate on.</li>
 *   <li>{@code send-approved-letter-to-correspondence} (P13_Task_SendToCorrespondence) hands the
 *       approved letter to the correspondence service and publishes the letter-entry message.</li>
 * </ul>
 *
 * <p>The letter itself is a record: the open step writes the draft the visit produced - the
 * patient it is for and the consultation it documents are read from the system of record - and the
 * steps that hand the letter on keep its status in step with where the letter is (draft, approved,
 * sent, complete, correction requested). The follow-up process 14 then starts from the letter that
 * is on record for the referral.
 */
@Component
public class ClinicLetterWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(ClinicLetterWorkers.class);

    /** The letter as it passes the roles of process 13. */
    private static final String DRAFT = "draft";
    private static final String APPROVED = "approved";
    private static final String SENT = "sent";
    private static final String COMPLETE = "complete";

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;

    public ClinicLetterWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
    }

    /** P13_Service_OpenLetter - open the clinic-letter record and set the timer correlation id. */
    @JobWorker(type = "open-clinic-letter")
    public Map<String, Object> openClinicLetter(final ActivatedJob job) {
        Map<String, Object> variables = letterVariables(job);
        String clinicLetterId = Variables.text(variables, HospitalMessages.CLINIC_LETTER_ID,
                "LET-" + patientId(variables));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(HospitalMessages.CLINIC_LETTER_ID, clinicLetterId);
        result.put("clinicLetterRecordOpened", true);
        result.put("clinicLetterOpenedDate", LocalDate.now().toString());

        LOG.info("patient {}: clinic-letter record {} opened for this visit (job {})",
                patientId(variables), clinicLetterId, job.getKey());

        recordLetter(job, variables, clinicLetterId, DRAFT);
        return result;
    }

    /** P13_Task_SendToCorrespondence - hand the approved letter to the correspondence service. */
    @JobWorker(type = "send-approved-letter-to-correspondence")
    public Map<String, Object> sendApprovedLetterToCorrespondence(final ActivatedJob job) {
        Map<String, Object> variables = letterVariables(job);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("correspondenceReference", "CORR-" + patientId(variables));
        answer.put("letterEntryReference", "ENTRY-" + patientId(variables));
        answer.put("letterEntryReceivedDate", LocalDate.now().toString());

        LOG.info("patient {}: approved letter handed to the correspondence service (job {})",
                patientId(variables), job.getKey());

        moveLetter(job, variables, SENT);

        messenger.publish(HospitalMessages.LETTER_ENTRY, variables, answer);
        return answer;
    }

    /** P13_Activity_0wdxagy - send the approved letter to the Medical Secretaries. */
    @JobWorker(type = "send-letter-to-secretaries")
    public Map<String, Object> sendLetterToSecretaries(final ActivatedJob job) {
        Map<String, Object> variables = letterVariables(job);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("letterEntryReference", "LET-" + patientId(variables));
        answer.put("letterEntryReceivedDate", LocalDate.now().toString());

        LOG.info("patient {}: approved clinic letter handed over for entry (job {})", patientId(variables), job.getKey());

        moveLetter(job, variables, APPROVED);

        messenger.publish(HospitalMessages.LETTER_FROM_DOCTOR, variables, answer);
        return answer;
    }

    /** P13_Activity_19r74id - distribute the checked letter to the verified recipients. */
    @JobWorker(type = "distribute-approved-letter")
    public Map<String, Object> distributeApprovedLetter(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String recipients = Variables.text(variables, "letterRecipients", "patient, referring clinician");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("distributionReference", "DIST-" + patientId(variables));
        result.put("distributionDate", LocalDate.now().toString());
        result.put("distributionMethod", Variables.text(variables, "distributionMethod", "hospital correspondence service"));
        result.put("letterComplete", true);

        LOG.info("patient {}: clinic letter distributed to {} (job {})", patientId(variables), recipients, job.getKey());

        moveLetter(job, variables, COMPLETE);
        return result;
    }

    /** P13_Activity_1t7mm3x - return the letter to the Consultant because of a suspected error. */
    @JobWorker(type = "return-letter-to-consultant")
    public Map<String, Object> returnLetterToConsultant(final ActivatedJob job) {
        Map<String, Object> variables = letterVariables(job);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("correctionRequestReference", "COR-" + patientId(variables));
        answer.put("correctionRequestedDate", LocalDate.now().toString());
        answer.put("letterStatus", "correction requested");

        LOG.info("patient {}: suspected clinical error, letter returned to the Consultant (job {})",
                patientId(variables), job.getKey());

        moveLetter(job, variables, "correction requested");

        messenger.publish(HospitalMessages.LETTER_BACK_FOR_RECHECKING, variables, answer);
        return answer;
    }

    /** P13_Activity_0aygpgf - send the weekly reminder while the letter is outstanding. */
    @JobWorker(type = "send-consultant-reminders")
    public Map<String, Object> sendConsultantReminders(final ActivatedJob job) {
        Map<String, Object> variables = letterVariables(job);
        int reminderCount = Variables.nextAttempt(variables, "reminderCount");

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("reminderCount", reminderCount);
        answer.put("reminderSentDate", LocalDate.now().toString());
        answer.put("consultantResponseReference", "RES-" + patientId(variables));
        answer.put("consultantResponse", "Consultant answered the reminder (simulated).");

        LOG.info("patient {}: weekly Consultant reminder {} sent (job {})",
                patientId(variables), reminderCount, job.getKey());

        messenger.publish(HospitalMessages.MESSAGE_FROM_DOCTOR, variables, answer);
        return answer;
    }

    /** P13_Activity_0ccs70w - escalate the still outstanding letter to the Administrative Manager. */
    @JobWorker(type = "escalate-letter-to-manager")
    public Map<String, Object> escalateLetterToManager(final ActivatedJob job) {
        Map<String, Object> variables = letterVariables(job);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("escalationReference", "ESC-" + patientId(variables));
        answer.put("escalationDate", LocalDate.now().toString());
        answer.put("escalationReport", "Administrative Manager reports the letter as still outstanding (simulated).");

        LOG.info("patient {}: outstanding letter escalated to the Administrative Manager (job {})",
                patientId(variables), job.getKey());

        messenger.publish(HospitalMessages.REPORT_FROM_DOCTOR, variables, answer);
        return answer;
    }

    /**
     * Opens the letter record of this visit.
     *
     * <p>The patient the letter is for and the consultation it documents are read from the system
     * of record: the letter has to name the patient the hospital has, and a letter that belongs to
     * a consultation names it, so the clinical content stays traceable. Its subject is the
     * patient's name as the record spells it, not as the process variable carried it.
     */
    private void recordLetter(ActivatedJob job, Map<String, Object> variables, String letterId, String status) {
        Optional<Patient> patient = database.patients().findById(patientId(variables));
        List<Consultation> consultations = database.consultations().findByReferralId(referralId(variables));
        LocalDate startedOn = Variables.date(variables, "letterStartDate",
                Variables.date(variables, "appointmentDate", LocalDate.now()));

        ClinicLetter letter = new ClinicLetter(
                letterId, referralId(variables), patientId(variables),
                consultations.isEmpty() ? null : consultations.get(consultations.size() - 1).consultationId(),
                Variables.text(variables, "letterType", "clinic letter"),
                Variables.text(variables, "letterRecipients", "patient, referring clinician"),
                "Clinic letter for " + patient.map(value -> value.givenName() + " " + value.familyName())
                        .orElse(Variables.text(variables, "patient_name", properties.getDefaultPatientName())),
                Variables.text(variables, "clinicalReviewFindings", Variables.text(variables, "diagnosis", null)),
                status,
                startedOn.atStartOfDay(),
                null);

        Optional<ClinicLetter> onRecord = database.clinicLetters().findById(letterId);
        if (onRecord.isEmpty()) {
            database.clinicLetters().insert(letter);
        } else {
            database.clinicLetters().update(letter);
        }
        LOG.info("patient {}: letter {} opened as '{}' for {} (job {})",
                letter.patientId(), letterId, status, letter.subject(), job.getKey());
    }

    /** Moves the letter of this referral to the status the step leaves it in. */
    private void moveLetter(ActivatedJob job, Map<String, Object> variables, String status) {
        String letterId = Variables.text(variables, HospitalMessages.CLINIC_LETTER_ID, "LET-" + patientId(variables));
        Optional<ClinicLetter> onRecord = database.clinicLetters().findById(letterId);
        onRecord.ifPresent(letter -> {
            database.clinicLetters().update(new ClinicLetter(letter.letterId(), letter.referralId(),
                    letter.patientId(), letter.consultationId(), letter.letterType(), letter.recipient(),
                    letter.subject(), letter.bodySummary(), status, letter.createdOn(),
                    COMPLETE.equals(status) ? LocalDateTime.now() : letter.sentOn()));
            LOG.info("patient {}: letter {} is now '{}' (job {})", letter.patientId(), letterId, status, job.getKey());
        });
    }

    /**
     * Phase 13 correlates on {@code patientId}. The clinic visit message starts the process with
     * that variable; the fallback keeps the worker usable when the clinic letter path is started
     * by hand while testing.
     */
    private Map<String, Object> letterVariables(ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        variables.putIfAbsent("patientId", properties.getDefaultPatientId());
        variables.putIfAbsent("referralId", properties.getDefaultReferralId());
        variables.putIfAbsent("patient_name", properties.getDefaultPatientName());
        return variables;
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, "patientId", properties.getDefaultPatientId());
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, "referralId", properties.getDefaultReferralId());
    }
}

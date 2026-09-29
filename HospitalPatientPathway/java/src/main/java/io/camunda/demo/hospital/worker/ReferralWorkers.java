package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Patient;
import io.camunda.demo.hospital.persistence.model.Referral;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 1 "Receive Referral" and phase 2 "Review Referral": the hospital talks to the referring
 * organisation.
 *
 * <ul>
 *   <li>{@code send-referral-documents-request} (R1_RequestDocs) asks for missing documents and
 *       hands the answer back as the {@code Requested referral documents} message, which the
 *       receive task R1_ReceiveDocs waits for.</li>
 *   <li>{@code send-referral-outcome} (R2_Decline and the throw event R2_SendAcceptedOutcome)
 *       tells the referring organisation whether the referral was accepted.</li>
 * </ul>
 *
 * <p>These two steps also put the referral into the system of record, because every other
 * aggregate of the pathway points at it: the patient's name and contact details that the referral
 * package carries are written onto the patient record the hospital keeps (the patient itself is
 * registered in the PAS by the intake step R1_RegisterCheck, which no worker implements), and the
 * referral is recorded with the status the step leaves it in - {@code received} on intake and
 * {@code accepted} or {@code declined} on review. A referral that is already on record is updated
 * instead of inserted, so replaying the step keeps one row per referral.
 */
@Component
public class ReferralWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(ReferralWorkers.class);

    /** Referral status after intake, and the two outcomes the review can leave. */
    private static final String RECEIVED = "received";

    private final ExternalPartyMessenger messenger;
    private final HospitalDatabase database;
    private final SimulationProperties properties;

    public ReferralWorkers(ExternalPartyMessenger messenger, HospitalDatabase database,
            SimulationProperties properties) {
        this.messenger = messenger;
        this.database = database;
        this.properties = properties;
    }

    /** R1_RequestDocs - send the missing-document request and record the returned documents. */
    @JobWorker(type = "send-referral-documents-request")
    public Map<String, Object> requestMissingDocuments(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String missingDocuments = Variables.text(variables, "missingDocuments", "referral letter");

        LOG.info("referral {}: asking the referring organisation for '{}' (job {})", referralId, missingDocuments, job.getKey());

        recordPatient(variables);
        recordReferral(variables, RECEIVED);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("receivedDocumentReferences", "DOC-" + referralId + "-01; DOC-" + referralId + "-02");
        answer.put("referralUpdateNotes", "Missing documents received from the referring organisation (simulated).");

        messenger.publish(HospitalMessages.REQUESTED_REFERRAL_DOCUMENTS, variables, answer);
        return answer;
    }

    /** R2_Decline / R2_SendAcceptedOutcome - tell the referring organisation the review outcome. */
    @JobWorker(type = "send-referral-outcome")
    public Map<String, Object> sendReviewOutcome(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        boolean suitable = Variables.flag(variables, "referralSuitable", false);

        String outcome = suitable ? "accepted" : "declined";
        recordPatient(variables);
        recordReferral(variables, outcome);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("referralOutcome", outcome);
        answer.put("referralDecisionReason", Variables.text(variables, "referralDecisionReason", "Simulated review outcome"));

        LOG.info("referral {}: informing the referring organisation that the referral was {} (job {})",
                referralId, answer.get("referralOutcome"), job.getKey());

        messenger.publish(HospitalMessages.REFERRAL_OUTCOME, variables, answer);
        return answer;
    }

    /**
     * Records the patient the referral is for: the name and the contact details the package carries
     * are written onto the patient record the hospital keeps.
     *
     * <p>A patient the hospital has no record of cannot be created from the pathway - the referral
     * package carries no date of birth, and the patient record is the PAS's, not the pathway's. The
     * intake step R1_RegisterCheck registers a new patient before the referral is worked, so a
     * missing record is a broken precondition and not something to fill in with a placeholder.
     */
    private void recordPatient(Map<String, Object> variables) {
        String patientId = patientId(variables);
        Patient onRecord = database.patients().findById(patientId).orElseThrow(() -> new IllegalStateException(
                "patient " + patientId + " is not in the system of record: the pathway works for the patients the "
                        + "PAS has registered (intake step R1_RegisterCheck)"));

        String name = Variables.text(variables, "patient_name", properties.getDefaultPatientName());
        String[] parts = nameParts(name);
        String contact = Variables.text(variables, "patientContact", null);
        String[] contactParts = contactParts(contact);

        Patient updated = new Patient(onRecord.patientId(), onRecord.nhsNumber(),
                parts[1], parts[0],
                onRecord.dateOfBirth(), onRecord.sex(),
                contactParts[0] == null ? onRecord.contactEmail() : contactParts[0],
                contactParts[1] == null ? onRecord.contactPhone() : contactParts[1],
                onRecord.interpreterRequired(), onRecord.registeredOn());
        database.patients().update(updated);

        LOG.info("patient {}: name and contact details of the referral package recorded as {} {}",
                patientId, updated.givenName(), updated.familyName());
    }

    /** Records the referral with this status, updating the row the intake already wrote. */
    private void recordReferral(Map<String, Object> variables, String status) {
        String referralId = referralId(variables);
        Optional<Referral> onRecord = database.referrals().findById(referralId);
        LocalDateTime receivedOn = Variables.date(variables, "referralReceivedDate", LocalDate.now())
                .atStartOfDay();

        Referral referral = new Referral(referralId, patientId(variables),
                Variables.text(variables, "referrerName",
                        onRecord.map(Referral::referringOrganisation).orElse("Referring organisation")),
                Variables.text(variables, "referringClinician",
                        onRecord.map(Referral::referringClinician).orElse(null)),
                Variables.text(variables, "referralPriority",
                        Variables.text(variables, "priority", onRecord.map(Referral::priority).orElse("routine"))),
                Variables.text(variables, "referralReason",
                        Variables.text(variables, "clinicalReferralFindings",
                                onRecord.map(Referral::suspectedCondition).orElse(null))),
                Variables.text(variables, "externalReference",
                        onRecord.map(Referral::externalReference).orElse(null)),
                onRecord.map(Referral::receivedOn).orElse(receivedOn),
                status);

        if (onRecord.isEmpty()) {
            database.referrals().insert(referral);
        } else {
            database.referrals().update(referral);
        }
        LOG.info("referral {}: recorded as {} for patient {}", referralId, status, referral.patientId());
    }

    /** The two name parts the patient record keeps, taken from the single name the pathway carries. */
    private String[] nameParts(String name) {
        int split = name.lastIndexOf(' ');
        return split < 1
                ? new String[] {name, name}
                : new String[] {name.substring(0, split), name.substring(split + 1)};
    }

    /**
     * The e-mail and the phone number in the single contact field of the referral package
     * ("alex.morgan@example.org / 07700 900123"); a part that is not there stays {@code null}.
     */
    private String[] contactParts(String contact) {
        String email = null;
        String phone = null;
        for (String part : String.valueOf(contact == null ? "" : contact).split("[/;,]")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (email == null && token.contains("@")) {
                email = token;
            } else if (phone == null && token.matches("\\+?[0-9][0-9 ()-]*")) {
                phone = token;
            }
        }
        return new String[] {email, phone};
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}

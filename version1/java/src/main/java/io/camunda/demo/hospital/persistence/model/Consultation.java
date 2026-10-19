package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * A consultation the clinician held, with the patient's consent decision attached to it.
 *
 * <p>Consent is recorded here rather than in a table of its own, because the model never asks for
 * consent independently of the consultation: the patient answers on the same form that documents
 * the consultation, and the two rows would always be created, updated and read together.
 *
 * @param consultationId    the consultation's own reference, the primary key
 * @param referralId        the referral under consultation
 * @param appointmentId     the appointment that was held, empty for an ad-hoc consultation
 * @param patientId         convenience copy of the referral's patient
 * @param clinicianId       who held the consultation
 * @param consultationType  face-to-face, telephone or video
 * @param occurredOn        when the consultation happened
 * @param recommendation    what the clinician recommends, as read by the next step of the pathway
 * @param outcome           outcome code the pathway branches on
 * @param consentStatus     the patient's decision: pending, consent or declined
 * @param consentRecordedBy who took the decision
 * @param consentRecordedOn when the decision was taken
 */
public record Consultation(
        String consultationId,
        String referralId,
        String appointmentId,
        String patientId,
        String clinicianId,
        String consultationType,
        LocalDateTime occurredOn,
        String recommendation,
        String outcome,
        String consentStatus,
        String consentRecordedBy,
        LocalDateTime consentRecordedOn) {
}

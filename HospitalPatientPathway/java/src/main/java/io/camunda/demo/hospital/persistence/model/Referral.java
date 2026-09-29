package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * The referral the whole pathway hangs off.
 *
 * <p>Every other aggregate points back at it, and its identifier is what the model's entry messages
 * correlate on ({@code referralId}), so this row is the natural join key between the Camunda
 * instance and the hospital's own records.
 *
 * @param referralId           the pathway's referral reference, the primary key
 * @param patientId            the patient the referral was written for
 * @param referringOrganisation practice or hospital that sent the referral
 * @param referringClinician   the person there who signed it
 * @param priority             routing priority: routine, urgent or two-week-wait
 * @param suspectedCondition   what the referrer suspects, as read by the reviewing clinician
 * @param externalReference    the referrer's own reference, echoed back in letters
 * @param receivedOn           when the hospital took the referral in
 * @param status               where the referral stands: received, triaged, scheduled, active, closed
 */
public record Referral(
        String referralId,
        String patientId,
        String referringOrganisation,
        String referringClinician,
        String priority,
        String suspectedCondition,
        String externalReference,
        LocalDateTime receivedOn,
        String status) {
}

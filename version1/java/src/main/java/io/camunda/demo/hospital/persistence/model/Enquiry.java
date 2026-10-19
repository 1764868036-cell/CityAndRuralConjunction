package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * A question about a pathway and the answer that was given to it.
 *
 * <p>The enquiry is deliberately loose about its subject: it may arrive before the hospital knows
 * which patient or referral it belongs to (the entry messages of the pathway can do the same), so
 * both references are optional and the contact details carry the answer instead.
 *
 * @param enquiryId     the enquiry's own reference, the primary key
 * @param patientId     the patient who asked, empty while the enquiry is unidentified
 * @param referralId    the referral the question is about, empty for a general enquiry
 * @param channel       how it arrived: email, letter or telephone
 * @param raisedBy      who asked
 * @param contactDetails the address or number to answer to
 * @param question      what was asked
 * @param response      the answer that was sent
 * @param status        open, answered or closed
 * @param receivedOn    when the enquiry arrived
 * @param answeredOn    when the answer went out
 */
public record Enquiry(
        String enquiryId,
        String patientId,
        String referralId,
        String channel,
        String raisedBy,
        String contactDetails,
        String question,
        String response,
        String status,
        LocalDateTime receivedOn,
        LocalDateTime answeredOn) {
}

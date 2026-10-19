package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The waitlist / slot search of a referral.
 *
 * <p>Separate from {@link Appointment} because the pathway asks for a slot before one exists: this
 * row records what was asked for, how often the scheduling service had to be retried, and - once
 * the offer arrived - which appointment resolved it.
 *
 * @param slotRequestId         the request's own reference, the primary key
 * @param referralId            the referral that waits for the slot
 * @param serviceCode           the catalogue entry a slot is needed for
 * @param earliestDate          earliest acceptable date
 * @param latestDate            latest acceptable date
 * @param availability          what the scheduling service answered: available or unavailable
 * @param attemptCount          how many times the pathway asked; drives the retry loop
 * @param status                open, offered, booked or abandoned
 * @param requestedOn           when the search started
 * @param resolvedAppointmentId the appointment the search ended in, empty while it is still open
 */
public record SlotRequest(
        String slotRequestId,
        String referralId,
        String serviceCode,
        LocalDate earliestDate,
        LocalDate latestDate,
        String availability,
        int attemptCount,
        String status,
        LocalDateTime requestedOn,
        String resolvedAppointmentId) {
}

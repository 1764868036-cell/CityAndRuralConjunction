package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * A consultation slot the scheduling service offered for the referral.
 *
 * <p>The row keeps what the patient answered as well, because the model branches on it: with an
 * accepted offer the pathway books the slot, with an alternative date the scheduling service has to
 * look again.
 *
 * @param appointmentId         the offer's own reference, the primary key
 * @param referralId            the referral the offer belongs to
 * @param patientId             convenience copy of the referral's patient, for patient-facing queries
 * @param serviceCode           the catalogue entry the slot was booked from
 * @param scheduledFor          date and time of the offered slot
 * @param location              clinic or ward the slot is in
 * @param clinicianId           the clinician who will hold the consultation
 * @param status                offered, booked, cancelled or attended
 * @param patientDecision       the patient's answer: accept, alternative or decline
 * @param alternativeOfferedFor the date the patient proposed instead, when they answered alternative
 */
public record Appointment(
        String appointmentId,
        String referralId,
        String patientId,
        String serviceCode,
        LocalDateTime scheduledFor,
        String location,
        String clinicianId,
        String status,
        String patientDecision,
        LocalDateTime alternativeOfferedFor) {
}

package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * A letter the pathway produced for the patient, the practice or the referring organisation.
 *
 * <p>The row keeps the summary rather than the rendered document: the pathway's letter worker
 * composes the text, the hospital's letter system owns the document, and the summary is what a
 * reprint or an audit reads.
 *
 * @param letterId       the letter's own reference, the primary key
 * @param referralId     the referral the letter is about
 * @param patientId      convenience copy of the referral's patient
 * @param consultationId the consultation the letter reports, empty for a referral-level letter
 * @param letterType     discharge, outcome, appointment or acknowledgement
 * @param recipient      who receives the letter, e.g. the patient or the referring practice
 * @param subject        one-line subject of the letter
 * @param bodySummary    summary of the content that was sent
 * @param status         draft, sent or failed
 * @param createdOn      when the letter worker composed it
 * @param sentOn         when the hospital's letter system accepted it
 */
public record ClinicLetter(
        String letterId,
        String referralId,
        String patientId,
        String consultationId,
        String letterType,
        String recipient,
        String subject,
        String bodySummary,
        String status,
        LocalDateTime createdOn,
        LocalDateTime sentOn) {
}

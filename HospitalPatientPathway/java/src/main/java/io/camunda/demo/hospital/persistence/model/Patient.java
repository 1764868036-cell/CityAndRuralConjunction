package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The person the pathway is run for.
 *
 * <p>The identifier is the pathway's own patient reference ({@code PAT-1001}), the value the model
 * carries in its {@code patientId} variable, so a worker can look the demographic record up
 * without translating between the model and the database.
 *
 * @param patientId           the pathway's patient reference, the primary key
 * @param nhsNumber           national patient number; unique, absent for a private patient
 * @param familyName          surname as the referral spelled it
 * @param givenName           first name as the referral spelled it
 * @param dateOfBirth         used to match the referral against the hospital's records
 * @param sex                 recorded sex, free text
 * @param contactEmail        where appointment offers and clinic letters go
 * @param contactPhone        where the scheduling service reaches the patient
 * @param interpreterRequired whether the patient needs an interpreter in the consultation
 * @param registeredOn        when the pathway first saw this patient
 */
public record Patient(
        String patientId,
        String nhsNumber,
        String familyName,
        String givenName,
        LocalDate dateOfBirth,
        String sex,
        String contactEmail,
        String contactPhone,
        boolean interpreterRequired,
        LocalDateTime registeredOn) {
}

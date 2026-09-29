package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDate;

/**
 * One pass through the treatment a service delivers.
 *
 * <p>The pathway repeats this aggregate while the clinical review says the test results fit to
 * continue, so the cycle number is part of the data and unique per referral and service rather than
 * an ordering detail of the table.
 *
 * @param treatmentCycleId           the cycle's own reference, the primary key
 * @param referralId                 the referral being treated
 * @param patientId                  convenience copy of the referral's patient
 * @param serviceCode                the catalogue entry the cycle delivers
 * @param cycleNumber                which pass this is, counted from 1 within referral and service
 * @param plannedStart               planned first day
 * @param plannedEnd                 planned last day
 * @param startedOn                  the day the treatment actually started
 * @param status                     planned, active, completed or stopped
 * @param testResultsFitToContinue   the clinical review's answer, which drives the loop
 * @param reviewOutcome              how the review was closed, e.g. continue, complete or escalate
 */
public record TreatmentCycle(
        String treatmentCycleId,
        String referralId,
        String patientId,
        String serviceCode,
        int cycleNumber,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        LocalDate startedOn,
        String status,
        Boolean testResultsFitToContinue,
        String reviewOutcome) {
}

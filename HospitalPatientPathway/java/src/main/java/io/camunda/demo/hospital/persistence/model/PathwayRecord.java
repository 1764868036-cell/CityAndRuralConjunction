package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The monitoring and follow-up record of a pathway that is running.
 *
 * <p>It is the bridge between the two systems: {@code processInstanceKey} ties the row to the
 * Camunda instance, the rest of the row is what the monitoring segment of the model needs to decide
 * whether somebody has to look at the pathway and when.
 *
 * @param pathwayRecordId   the record's own reference, the primary key
 * @param referralId        the referral being monitored
 * @param patientId         convenience copy of the referral's patient
 * @param processInstanceKey the Camunda process instance, absent until the pathway reported itself
 * @param currentStage      the stage the pathway is in, as reported by the workers
 * @param monitoringFlag    whether the pathway is under active monitoring
 * @param followUpDue       when the next review is due
 * @param lastReviewedOn    when it was reviewed last
 * @param status            active, on-hold or closed
 * @param notes             free text the reviewing person left
 */
public record PathwayRecord(
        String pathwayRecordId,
        String referralId,
        String patientId,
        Long processInstanceKey,
        String currentStage,
        boolean monitoringFlag,
        LocalDate followUpDue,
        LocalDateTime lastReviewedOn,
        String status,
        String notes) {
}

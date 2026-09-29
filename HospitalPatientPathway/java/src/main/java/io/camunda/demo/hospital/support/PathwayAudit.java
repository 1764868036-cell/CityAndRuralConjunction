package io.camunda.demo.hospital.support;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.EventLog;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The audit trail of the pathway: what a worker completed and what arrived from outside.
 *
 * <p>Two steps of the model own the trail and say so in their names: {@code P16_Task_Audit}
 * ("Record the access decision in the protected audit log") and {@code P15_Task_UpdateMonitoringRecord}
 * ("Update the monitoring dataset and audit trail"). The simulation of the outside world records
 * the corridor messages it sends, because a message that arrives from a person or an external
 * organisation is exactly what nothing else in the pathway can prove afterwards. Every other step
 * leaves its mark in the business object it writes instead, so the trail stays the exception and
 * not a second copy of the schema.
 *
 * <p>The trail records the subject of an event without requiring the pathway to know it: the
 * referral and the patient are written as the variables carry them, and the log's columns are not
 * foreign keys, so an entry message for a referral the hospital has not registered yet is recorded
 * instead of failing the step that received it.
 *
 * <p>Nothing here is allowed to fail the step that reports to it. The trail is evidence about the
 * pathway, not a gate in it: a worker completes the job it was activated for and a corridor
 * message that the pathway waits for must leave the hospital even when the log cannot be written.
 * A trail that is down is therefore logged as a warning, and the tests that assert the recorded
 * rows are what catches a trail that stopped working altogether.
 */
@Component
public class PathwayAudit {

    private static final Logger LOG = LoggerFactory.getLogger(PathwayAudit.class);

    private final HospitalDatabase database;

    public PathwayAudit(HospitalDatabase database) {
        this.database = database;
    }

    /**
     * Appends one completed job to the trail.
     *
     * <p>The event id names the job and adds a unique tail, because the engine keeps the job key of
     * a job it retries: every line of the trail is written once, and a retry shows up as a second
     * line for the same job key rather than as a duplicate primary key that would fail the job.
     */
    public void jobCompleted(ActivatedJob job, Map<String, Object> variables) {
        append(new EventLog(
                "EVT-" + job.getKey() + "-" + UUID.randomUUID().toString().substring(0, 8),
                job.getProcessInstanceKey(),
                job.getElementId(),
                job.getType(),
                null,
                "job-completed",
                Variables.text(variables, HospitalMessages.REFERRAL_ID, null),
                Variables.text(variables, HospitalMessages.PATIENT_ID, null),
                LocalDateTime.now(),
                job.getVariables()), "job " + job.getKey());
    }

    /** Appends one message that reached the pathway from outside the process. */
    public void messageReceived(String messageName, Map<String, Object> variables) {
        append(new EventLog(
                "EVT-" + UUID.randomUUID(),
                null,
                null,
                null,
                messageName,
                "message-received",
                Variables.text(variables, HospitalMessages.REFERRAL_ID, null),
                Variables.text(variables, HospitalMessages.PATIENT_ID, null),
                LocalDateTime.now(),
                String.valueOf(variables)), "message '" + messageName + "'");
    }

    /** Writes one line of the trail; a failure is reported and never handed to the caller. */
    private void append(EventLog event, String subject) {
        try {
            database.eventLog().append(event);
        } catch (RuntimeException e) {
            LOG.warn("could not record {} in the audit trail: {}", subject, e.getMessage());
        }
    }
}

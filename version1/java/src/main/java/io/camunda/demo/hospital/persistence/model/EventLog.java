package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * One thing that happened to the pathway, kept as the audit trail of the demo.
 *
 * <p>The log has to survive the two things a process cannot show afterwards: a job that was
 * completed by a worker, and a message that arrived from outside the instance. Both are recorded
 * with the process instance key, the BPMN element and the payload, so the model's behaviour can be
 * reconstructed from the database alone.
 *
 * <p>The referral and the patient are subject references, not references the database enforces:
 * events arrive before the hospital has the subject on record. Both are therefore nullable and are
 * written as read from the variables - the trail names what the event was about without claiming
 * that the pathway already knows it.
 *
 * @param eventId            the log entry's own reference, the primary key
 * @param processInstanceKey the Camunda instance the event belongs to, absent for a message that arrived early
 * @param elementId          the BPMN element the event came from, absent for a message
 * @param jobType            the job type of the worker that reported it, absent for a message
 * @param messageName        the name of the message that was handled, absent for a job
 * @param eventType          what was recorded: {@code job-completed} or {@code message-received}
 * @param referralId         the referral the event belongs to, absent while unknown
 * @param patientId          the patient the event belongs to, absent while unknown
 * @param occurredOn         when the event happened
 * @param payload            the variables or message payload that was recorded
 */
public record EventLog(
        String eventId,
        Long processInstanceKey,
        String elementId,
        String jobType,
        String messageName,
        String eventType,
        String referralId,
        String patientId,
        LocalDateTime occurredOn,
        String payload) {
}

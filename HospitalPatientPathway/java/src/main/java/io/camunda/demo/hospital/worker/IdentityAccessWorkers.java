package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.AccessAudit;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.PathwayAudit;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Process 16 "Manage identities, permissions and system interruptions": the two service tasks
 * that have to leave a trace.
 *
 * <ul>
 *   <li>{@code record-access-decision} (P16_Task_Audit) writes the access decision to the
 *       protected audit log.</li>
 *   <li>{@code reconcile-offline-work} (P16_Task_Reconcile) reconciles the work recorded during a
 *       system interruption and preserves its audit history.</li>
 * </ul>
 *
 * <p>Both steps own the audit trail: the decision is appended to {@code access_audit} (which is
 * append-only by design, so a later withdrawal is a new row) and to {@code event_log}, and the
 * reconciliation reads the trail of this referral back to say how much history it preserved.
 */
@Component
public class IdentityAccessWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityAccessWorkers.class);

    private final HospitalDatabase database;
    private final SimulationProperties properties;
    private final PathwayAudit audit;

    public IdentityAccessWorkers(HospitalDatabase database, SimulationProperties properties, PathwayAudit audit) {
        this.database = database;
        this.properties = properties;
        this.audit = audit;
    }

    /** P16_Task_Audit - record the access decision in the protected audit log. */
    @JobWorker(type = "record-access-decision")
    public Map<String, Object> recordAccessDecision(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String staffUserId = Variables.text(variables, "targetStaffUserId", "unknown-user");
        String role = Variables.text(variables, "approvedRole", Variables.text(variables, "requestedRole", "unchanged"));
        boolean authorised = Variables.flag(variables, "accessAuthorised", true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auditRecordReference", "AUD-" + job.getKey());
        result.put("auditRecordedAt", LocalDateTime.now().toString());
        result.put("auditRecordedBy", "hospital-pathway-worker");
        result.put("auditEntry", "Access decision for " + staffUserId + " (role " + role + ") recorded (simulated audit log entry).");

        LOG.info("identity {}: access decision audited as {} (job {})", staffUserId, result.get("auditRecordReference"), job.getKey());

        database.accessAudits().append(new AccessAudit(
                "AUD-" + job.getKey(), staffUserId,
                Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId()),
                Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId()),
                Variables.text(variables, "requestType", "access review"),
                authorised ? "approved" : "rejected",
                Variables.text(variables, "permissionRationale", Variables.text(variables, "verificationNotes", null)),
                Variables.text(variables, "authorisingAdministrator", "hospital-pathway-worker"),
                LocalDateTime.now()));

        audit.jobCompleted(job, variables);
        return result;
    }

    /** P16_Task_Reconcile - reconcile the offline records and keep their audit history. */
    @JobWorker(type = "reconcile-offline-work")
    public Map<String, Object> reconcileOfflineWork(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String incidentReference = Variables.text(variables, "incidentReference", "INC-" + job.getKey());
        String referralId = Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());

        // What the interruption left behind is exactly what the two trails hold: the decisions of
        // the identity segment and the events of the pathway. Reading them back is the
        // reconciliation - the count says how much history is kept.
        List<AccessAudit> decisions = database.accessAudits().findByTargetReferralId(referralId);
        int events = database.eventLog().findByReferralId(referralId).size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reconciliationReference", "REC-" + incidentReference);
        result.put("reconciledOfflineRecords", events);
        result.put("reconciledAuditRecords", decisions.size());
        result.put("reconciledAt", LocalDateTime.now().toString());
        result.put("auditHistoryPreserved", true);

        LOG.info("incident {}: offline work reconciled as {} over {} audit record(s) and {} event(s), "
                        + "audit history preserved (job {})",
                incidentReference, result.get("reconciliationReference"), decisions.size(), events, job.getKey());
        return result;
    }
}

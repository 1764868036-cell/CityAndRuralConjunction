package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.AccessAudit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The access decisions of the identity and access segment (table {@code access_audit}).
 *
 * <p>Append-only by design: the repository offers an {@code append} and the finders of the base
 * class, but no update. An audit row that could be rewritten afterwards would not prove who decided
 * what, so a later withdrawal of access is a new row with a new reference.
 */
@Component
public class AccessAuditRepository extends JdbcRepository<AccessAudit, String> {

    AccessAuditRepository(JdbcClient jdbc) {
        super(jdbc, "access_audit", "audit_id");
    }

    /** Appends one access decision to the audit. */
    public void append(AccessAudit audit) {
        jdbc().sql("""
                INSERT INTO access_audit (audit_id, staff_user_id, target_patient_id, target_referral_id, action,
                                          decision, reason, decided_by, decided_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(audit.auditId(), audit.staffUserId(), audit.targetPatientId(), audit.targetReferralId(),
                        audit.action(), audit.decision(), audit.reason(), audit.decidedBy(), audit.decidedOn())
                .update();
    }

    /** The decisions about one member of staff, oldest first. */
    public List<AccessAudit> findByStaffUserId(String staffUserId) {
        return jdbc().sql("SELECT * FROM access_audit WHERE staff_user_id = ? ORDER BY decided_on")
                .param(staffUserId)
                .query(rows())
                .list();
    }

    /** The decisions about one referral, oldest first. */
    public List<AccessAudit> findByTargetReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM access_audit WHERE target_referral_id = ? ORDER BY decided_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    @Override
    protected AccessAudit mapRow(ResultSet row, int index) throws SQLException {
        return new AccessAudit(
                row.getString("audit_id"),
                row.getString("staff_user_id"),
                row.getString("target_patient_id"),
                row.getString("target_referral_id"),
                row.getString("action"),
                row.getString("decision"),
                row.getString("reason"),
                row.getString("decided_by"),
                row.getObject("decided_on", LocalDateTime.class));
    }
}

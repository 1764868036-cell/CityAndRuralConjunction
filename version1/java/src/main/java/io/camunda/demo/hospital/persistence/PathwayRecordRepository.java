package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.PathwayRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The monitoring and follow-up records of the pathway (table {@code pathway_record}).
 *
 * <p>This is the only aggregate that names the Camunda process instance, so it is the bridge the
 * monitoring step uses to connect the model's running instance with the hospital's own view of the
 * pathway; {@code findByProcessInstanceKey} is that lookup.
 */
@Component
public class PathwayRecordRepository extends JdbcRepository<PathwayRecord, String> {

    PathwayRecordRepository(JdbcClient jdbc) {
        super(jdbc, "pathway_record", "pathway_record_id");
    }

    /** Opens the monitoring record of a pathway that reported itself. */
    public void insert(PathwayRecord record) {
        jdbc().sql("""
                INSERT INTO pathway_record (pathway_record_id, referral_id, patient_id, process_instance_key,
                                            current_stage, monitoring_flag, follow_up_due, last_reviewed_on,
                                            status, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(record.pathwayRecordId(), record.referralId(), record.patientId(),
                        record.processInstanceKey(), record.currentStage(), record.monitoringFlag(),
                        record.followUpDue(), record.lastReviewedOn(), record.status(), record.notes())
                .update();
    }

    /** Overwrites the mutable fields of a record, which is how a stage change or a review is recorded. */
    public void update(PathwayRecord record) {
        jdbc().sql("""
                UPDATE pathway_record
                   SET referral_id = ?, patient_id = ?, process_instance_key = ?, current_stage = ?,
                       monitoring_flag = ?, follow_up_due = ?, last_reviewed_on = ?, status = ?, notes = ?
                 WHERE pathway_record_id = ?
                """)
                .params(record.referralId(), record.patientId(), record.processInstanceKey(),
                        record.currentStage(), record.monitoringFlag(), record.followUpDue(),
                        record.lastReviewedOn(), record.status(), record.notes(), record.pathwayRecordId())
                .update();
    }

    /** The records of one referral, oldest first. */
    public List<PathwayRecord> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM pathway_record WHERE referral_id = ? ORDER BY pathway_record_id")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The record the pathway registered for a Camunda instance, the newest first. */
    public Optional<PathwayRecord> findByProcessInstanceKey(long processInstanceKey) {
        return jdbc().sql("SELECT * FROM pathway_record WHERE process_instance_key = ? "
                        + "ORDER BY last_reviewed_on DESC FETCH FIRST 1 ROW ONLY")
                .param(processInstanceKey)
                .query(rows())
                .optional();
    }

    /** The pathways that are under active monitoring and whose review is due. */
    public List<PathwayRecord> findDueForReview(LocalDate day) {
        return jdbc().sql("SELECT * FROM pathway_record WHERE monitoring_flag = TRUE AND follow_up_due <= ? "
                        + "ORDER BY follow_up_due")
                .param(day)
                .query(rows())
                .list();
    }

    @Override
    protected PathwayRecord mapRow(ResultSet row, int index) throws SQLException {
        return new PathwayRecord(
                row.getString("pathway_record_id"),
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getObject("process_instance_key", Long.class),
                row.getString("current_stage"),
                row.getBoolean("monitoring_flag"),
                row.getObject("follow_up_due", LocalDate.class),
                row.getObject("last_reviewed_on", LocalDateTime.class),
                row.getString("status"),
                row.getString("notes"));
    }
}

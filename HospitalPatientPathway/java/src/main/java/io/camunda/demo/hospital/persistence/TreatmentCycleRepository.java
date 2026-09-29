package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.TreatmentCycle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The treatment cycles of the pathway (table {@code treatment_cycle}).
 *
 * <p>The cycle number is data, not an ordering detail: the clinical review decides whether the
 * pathway returns to this aggregate for the next pass, and the number is what tells the next pass
 * apart from the one before it.
 */
@Component
public class TreatmentCycleRepository extends JdbcRepository<TreatmentCycle, String> {

    TreatmentCycleRepository(JdbcClient jdbc) {
        super(jdbc, "treatment_cycle", "treatment_cycle_id");
    }

    /** Plans the next pass through the treatment. */
    public void insert(TreatmentCycle cycle) {
        jdbc().sql("""
                INSERT INTO treatment_cycle (treatment_cycle_id, referral_id, patient_id, service_code,
                                             cycle_number, planned_start, planned_end, started_on, status,
                                             test_results_fit_to_continue, review_outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(cycle.treatmentCycleId(), cycle.referralId(), cycle.patientId(), cycle.serviceCode(),
                        cycle.cycleNumber(), cycle.plannedStart(), cycle.plannedEnd(), cycle.startedOn(),
                        cycle.status(), cycle.testResultsFitToContinue(), cycle.reviewOutcome())
                .update();
    }

    /** Overwrites the mutable fields of a cycle, which is how the review and the end date are recorded. */
    public void update(TreatmentCycle cycle) {
        jdbc().sql("""
                UPDATE treatment_cycle
                   SET referral_id = ?, patient_id = ?, service_code = ?, cycle_number = ?, planned_start = ?,
                       planned_end = ?, started_on = ?, status = ?, test_results_fit_to_continue = ?,
                       review_outcome = ?
                 WHERE treatment_cycle_id = ?
                """)
                .params(cycle.referralId(), cycle.patientId(), cycle.serviceCode(), cycle.cycleNumber(),
                        cycle.plannedStart(), cycle.plannedEnd(), cycle.startedOn(), cycle.status(),
                        cycle.testResultsFitToContinue(), cycle.reviewOutcome(), cycle.treatmentCycleId())
                .update();
    }

    /** The cycles of one referral, in the order they were delivered. */
    public List<TreatmentCycle> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM treatment_cycle WHERE referral_id = ? ORDER BY cycle_number")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The pass with this number for a referral and service. */
    public Optional<TreatmentCycle> findByReferralServiceAndNumber(String referralId, String serviceCode,
            int cycleNumber) {
        return jdbc().sql("SELECT * FROM treatment_cycle WHERE referral_id = ? AND service_code = ? "
                        + "AND cycle_number = ?")
                .params(referralId, serviceCode, cycleNumber)
                .query(rows())
                .optional();
    }

    /** The highest cycle number reached for a referral and service; {@code 0} before the first pass. */
    public int highestCycleNumber(String referralId, String serviceCode) {
        return jdbc().sql("SELECT COALESCE(MAX(cycle_number), 0) FROM treatment_cycle "
                        + "WHERE referral_id = ? AND service_code = ?")
                .params(referralId, serviceCode)
                .query(Integer.class)
                .single();
    }

    @Override
    protected TreatmentCycle mapRow(ResultSet row, int index) throws SQLException {
        return new TreatmentCycle(
                row.getString("treatment_cycle_id"),
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getString("service_code"),
                row.getInt("cycle_number"),
                row.getObject("planned_start", LocalDate.class),
                row.getObject("planned_end", LocalDate.class),
                row.getObject("started_on", LocalDate.class),
                row.getString("status"),
                row.getObject("test_results_fit_to_continue", Boolean.class),
                row.getString("review_outcome"));
    }
}

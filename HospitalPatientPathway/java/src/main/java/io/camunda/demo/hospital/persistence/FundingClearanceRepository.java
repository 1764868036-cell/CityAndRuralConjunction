package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.FundingClearance;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The funding clearances of the pathway (table {@code funding_clearance}).
 *
 * <p>One authorisation can be cleared more than once (a partial release, a corrected release), so
 * the clearances are their own table and the finance step lists them per authorisation.
 */
@Component
public class FundingClearanceRepository extends JdbcRepository<FundingClearance, String> {

    FundingClearanceRepository(JdbcClient jdbc) {
        super(jdbc, "funding_clearance", "clearance_id");
    }

    /** Writes the money that was released. */
    public void insert(FundingClearance clearance) {
        jdbc().sql("""
                INSERT INTO funding_clearance (clearance_id, authorisation_id, referral_id, clearance_reference,
                                               cleared_amount, currency, cleared_on, valid_until, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(clearance.clearanceId(), clearance.authorisationId(), clearance.referralId(),
                        clearance.clearanceReference(), clearance.clearedAmount(), clearance.currency(),
                        clearance.clearedOn(), clearance.validUntil(), clearance.status())
                .update();
    }

    /** Overwrites the mutable fields of a clearance, which is how a release is drawn or expires. */
    public void update(FundingClearance clearance) {
        jdbc().sql("""
                UPDATE funding_clearance
                   SET authorisation_id = ?, referral_id = ?, clearance_reference = ?, cleared_amount = ?,
                       currency = ?, cleared_on = ?, valid_until = ?, status = ?
                 WHERE clearance_id = ?
                """)
                .params(clearance.authorisationId(), clearance.referralId(), clearance.clearanceReference(),
                        clearance.clearedAmount(), clearance.currency(), clearance.clearedOn(),
                        clearance.validUntil(), clearance.status(), clearance.clearanceId())
                .update();
    }

    /** The clearances released against one authorisation, oldest first. */
    public List<FundingClearance> findByAuthorisationId(String authorisationId) {
        return jdbc().sql("SELECT * FROM funding_clearance WHERE authorisation_id = ? ORDER BY cleared_on")
                .param(authorisationId)
                .query(rows())
                .list();
    }

    /** The clearance of a referral that is still open for drawing, the newest first. */
    public Optional<FundingClearance> findDrawableByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM funding_clearance WHERE referral_id = ? AND status = 'cleared' "
                        + "ORDER BY cleared_on DESC FETCH FIRST 1 ROW ONLY")
                .param(referralId)
                .query(rows())
                .optional();
    }

    @Override
    protected FundingClearance mapRow(ResultSet row, int index) throws SQLException {
        return new FundingClearance(
                row.getString("clearance_id"),
                row.getString("authorisation_id"),
                row.getString("referral_id"),
                row.getString("clearance_reference"),
                row.getObject("cleared_amount", BigDecimal.class),
                row.getString("currency"),
                row.getObject("cleared_on", LocalDateTime.class),
                row.getObject("valid_until", LocalDate.class),
                row.getString("status"));
    }
}

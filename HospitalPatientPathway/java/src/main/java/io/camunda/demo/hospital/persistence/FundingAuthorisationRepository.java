package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.FundingAuthorisation;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The funding requests of the pathway (table {@code funding_authorisation}).
 *
 * <p>Request and decision share one row because the pathway always asks before it decides: the
 * worker writes the request when it publishes it and completes the same row with the funder's
 * answer, so a declined request stays visible with its reason.
 */
@Component
public class FundingAuthorisationRepository extends JdbcRepository<FundingAuthorisation, String> {

    FundingAuthorisationRepository(JdbcClient jdbc) {
        super(jdbc, "funding_authorisation", "authorisation_id");
    }

    /** Writes the request that is about to go to the funder. */
    public void insert(FundingAuthorisation authorisation) {
        jdbc().sql("""
                INSERT INTO funding_authorisation (authorisation_id, referral_id, patient_id, treatment_cycle_id,
                                                  funder_code, requested_amount, authorised_amount, currency, status,
                                                  decision_reason, requested_on, decided_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(authorisation.authorisationId(), authorisation.referralId(), authorisation.patientId(),
                        authorisation.treatmentCycleId(), authorisation.funderCode(),
                        authorisation.requestedAmount(), authorisation.authorisedAmount(),
                        authorisation.currency(), authorisation.status(), authorisation.decisionReason(),
                        authorisation.requestedOn(), authorisation.decidedOn())
                .update();
    }

    /** Overwrites the mutable fields of a request, which is how the funder's decision is recorded. */
    public void update(FundingAuthorisation authorisation) {
        jdbc().sql("""
                UPDATE funding_authorisation
                   SET referral_id = ?, patient_id = ?, treatment_cycle_id = ?, funder_code = ?,
                       requested_amount = ?, authorised_amount = ?, currency = ?, status = ?,
                       decision_reason = ?, decided_on = ?
                 WHERE authorisation_id = ?
                """)
                .params(authorisation.referralId(), authorisation.patientId(), authorisation.treatmentCycleId(),
                        authorisation.funderCode(), authorisation.requestedAmount(),
                        authorisation.authorisedAmount(), authorisation.currency(), authorisation.status(),
                        authorisation.decisionReason(), authorisation.decidedOn(),
                        authorisation.authorisationId())
                .update();
    }

    /** The funding requests of one referral, oldest first. */
    public List<FundingAuthorisation> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM funding_authorisation WHERE referral_id = ? ORDER BY requested_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** Sum of what was granted for a referral; zero before the funder answered. */
    public BigDecimal authorisedTotal(String referralId) {
        return jdbc().sql("SELECT COALESCE(SUM(authorised_amount), 0) FROM funding_authorisation "
                        + "WHERE referral_id = ?")
                .param(referralId)
                .query(BigDecimal.class)
                .single();
    }

    @Override
    protected FundingAuthorisation mapRow(ResultSet row, int index) throws SQLException {
        return new FundingAuthorisation(
                row.getString("authorisation_id"),
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getString("treatment_cycle_id"),
                row.getString("funder_code"),
                row.getObject("requested_amount", BigDecimal.class),
                row.getObject("authorised_amount", BigDecimal.class),
                row.getString("currency"),
                row.getString("status"),
                row.getString("decision_reason"),
                row.getObject("requested_on", LocalDateTime.class),
                row.getObject("decided_on", LocalDateTime.class));
    }
}

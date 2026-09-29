package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.RefundCase;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The refund and fund-transfer cases of the pathway (table {@code refund_case}).
 *
 * <p>Both cases move money away from a charge and the model runs the same steps for either of them,
 * so they share a table and a repository; {@code caseType} says whether the patient gets the money
 * back or another booking does.
 */
@Component
public class RefundCaseRepository extends JdbcRepository<RefundCase, String> {

    RefundCaseRepository(JdbcClient jdbc) {
        super(jdbc, "refund_case", "refund_case_id");
    }

    /** Opens a case for a charge that has to be reversed. */
    public void insert(RefundCase refundCase) {
        jdbc().sql("""
                INSERT INTO refund_case (refund_case_id, payment_id, referral_id, case_type, amount, currency,
                                         status, outcome, linked_booking_reference, opened_on, closed_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(refundCase.refundCaseId(), refundCase.paymentId(), refundCase.referralId(),
                        refundCase.caseType(), refundCase.amount(), refundCase.currency(), refundCase.status(),
                        refundCase.outcome(), refundCase.linkedBookingReference(), refundCase.openedOn(),
                        refundCase.closedOn())
                .update();
    }

    /** Overwrites the mutable fields of a case, which is how the provider's answer is recorded. */
    public void update(RefundCase refundCase) {
        jdbc().sql("""
                UPDATE refund_case
                   SET payment_id = ?, referral_id = ?, case_type = ?, amount = ?, currency = ?, status = ?,
                       outcome = ?, linked_booking_reference = ?, closed_on = ?
                 WHERE refund_case_id = ?
                """)
                .params(refundCase.paymentId(), refundCase.referralId(), refundCase.caseType(),
                        refundCase.amount(), refundCase.currency(), refundCase.status(), refundCase.outcome(),
                        refundCase.linkedBookingReference(), refundCase.closedOn(), refundCase.refundCaseId())
                .update();
    }

    /** The cases of one referral, oldest first. */
    public List<RefundCase> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM refund_case WHERE referral_id = ? ORDER BY opened_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The cases opened for one charge. */
    public List<RefundCase> findByPaymentId(String paymentId) {
        return jdbc().sql("SELECT * FROM refund_case WHERE payment_id = ? ORDER BY opened_on")
                .param(paymentId)
                .query(rows())
                .list();
    }

    /**
     * The case of a referral and type that is still open, the newest first.
     *
     * <p>A closed case stays readable through {@link #findByReferralId(String)}, so a step that has
     * to decide between inserting and updating asks the primary key, not this finder.
     */
    public Optional<RefundCase> findOpenByReferralId(String referralId, String caseType) {
        return jdbc().sql("SELECT * FROM refund_case WHERE referral_id = ? AND case_type = ? AND status = 'open' "
                        + "ORDER BY opened_on DESC FETCH FIRST 1 ROW ONLY")
                .params(referralId, caseType)
                .query(rows())
                .optional();
    }

    @Override
    protected RefundCase mapRow(ResultSet row, int index) throws SQLException {
        return new RefundCase(
                row.getString("refund_case_id"),
                row.getString("payment_id"),
                row.getString("referral_id"),
                row.getString("case_type"),
                row.getObject("amount", BigDecimal.class),
                row.getString("currency"),
                row.getString("status"),
                row.getString("outcome"),
                row.getString("linked_booking_reference"),
                row.getObject("opened_on", LocalDateTime.class),
                row.getObject("closed_on", LocalDateTime.class));
    }
}

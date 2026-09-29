package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.Payment;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The charges and their payment results (table {@code payment}).
 *
 * <p>The pathway keeps one charge per referral, with one lifecycle: the advance-payment step opens
 * the row with the status {@code requested}, the payment step completes the same row with the
 * provider's answer ({@code paid} or {@code failed}) and the refund and fund-transfer steps reverse
 * the charge that collected money. The row therefore shows both the attempt and its outcome - the
 * state the money steps need when they look for what can be moved. The finders below still return a
 * single best row rather than assuming there is only one, because the table itself allows a second
 * charge (a corrected invoice, a charge raised outside the pathway).
 */
@Component
public class PaymentRepository extends JdbcRepository<Payment, String> {

    PaymentRepository(JdbcClient jdbc) {
        super(jdbc, "payment", "payment_id");
    }

    /** Writes the charge that is about to go to the payment provider. */
    public void insert(Payment payment) {
        jdbc().sql("""
                INSERT INTO payment (payment_id, referral_id, patient_id, charge_reference, amount, currency,
                                     status, provider_reference, prior_charge_found, requested_on, settled_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(payment.paymentId(), payment.referralId(), payment.patientId(), payment.chargeReference(),
                        payment.amount(), payment.currency(), payment.status(), payment.providerReference(),
                        payment.priorChargeFound(), payment.requestedOn(), payment.settledOn())
                .update();
    }

    /** Overwrites the mutable fields of a charge, which is how the provider's answer is recorded. */
    public void update(Payment payment) {
        jdbc().sql("""
                UPDATE payment
                   SET referral_id = ?, patient_id = ?, charge_reference = ?, amount = ?, currency = ?,
                       status = ?, provider_reference = ?, prior_charge_found = ?, settled_on = ?
                 WHERE payment_id = ?
                """)
                .params(payment.referralId(), payment.patientId(), payment.chargeReference(), payment.amount(),
                        payment.currency(), payment.status(), payment.providerReference(),
                        payment.priorChargeFound(), payment.settledOn(), payment.paymentId())
                .update();
    }

    /** The charges of one referral, oldest first. */
    public List<Payment> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM payment WHERE referral_id = ? ORDER BY requested_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    /**
     * The charge of a referral that collected money (status {@code paid}), the newest first.
     *
     * <p>Whether the money has already been reversed is deliberately not asked here: the steps that
     * move money (the refund and fund-transfer cases) need the charge that was collected, and the
     * case on record is what records the reversal.
     */
    public Optional<Payment> findPaidByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM payment WHERE referral_id = ? AND status = 'paid' "
                        + "ORDER BY requested_on DESC FETCH FIRST 1 ROW ONLY")
                .param(referralId)
                .query(rows())
                .optional();
    }

    @Override
    protected Payment mapRow(ResultSet row, int index) throws SQLException {
        return new Payment(
                row.getString("payment_id"),
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getString("charge_reference"),
                row.getObject("amount", BigDecimal.class),
                row.getString("currency"),
                row.getString("status"),
                row.getString("provider_reference"),
                row.getBoolean("prior_charge_found"),
                row.getObject("requested_on", LocalDateTime.class),
                row.getObject("settled_on", LocalDateTime.class));
    }
}

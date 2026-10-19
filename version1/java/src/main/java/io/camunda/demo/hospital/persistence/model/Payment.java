package io.camunda.demo.hospital.persistence.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The charge raised for the treatment and what the payment provider answered.
 *
 * <p>{@code priorChargeFound} is kept on the row instead of being derived, because the pathway
 * branches on it when it looks for an earlier booking of the same treatment - the answer has to
 * stay visible after the branch.
 *
 * @param paymentId        the payment's own reference, the primary key
 * @param referralId       the referral that is charged
 * @param patientId        convenience copy of the referral's patient
 * @param chargeReference  the hospital's charge reference, printed on the patient's account
 * @param amount           the amount charged
 * @param currency         ISO code of the amount
 * @param status           requested, paid or failed
 * @param providerReference the payment provider's reference, absent until it answered
 * @param priorChargeFound whether an earlier booking of the same treatment was found
 * @param requestedOn      when the charge went out
 * @param settledOn        when the provider settled the charge
 */
public record Payment(
        String paymentId,
        String referralId,
        String patientId,
        String chargeReference,
        BigDecimal amount,
        String currency,
        String status,
        String providerReference,
        boolean priorChargeFound,
        LocalDateTime requestedOn,
        LocalDateTime settledOn) {
}

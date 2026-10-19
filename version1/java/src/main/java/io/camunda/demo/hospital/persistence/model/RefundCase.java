package io.camunda.demo.hospital.persistence.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Money on its way back to the patient, or on to another booking.
 *
 * <p>One aggregate for both cases, because the pathway runs the same steps for the refund and for
 * the fund transfer: ask the provider, record its answer, link the result to the booking the money
 * belongs to. {@code caseType} says which of the two it is.
 *
 * @param refundCaseId           the case's own reference, the primary key
 * @param paymentId              the charge the money comes from
 * @param referralId             the referral the case belongs to
 * @param caseType               refund (money back to the patient) or fund-transfer (money to another booking)
 * @param amount                 the amount to move
 * @param currency               ISO code of the amount
 * @param status                 open, completed or rejected
 * @param outcome                the provider's answer, e.g. completed or rejected
 * @param linkedBookingReference the booking the money was linked to
 * @param openedOn               when the case was opened
 * @param closedOn               when the provider closed it
 */
public record RefundCase(
        String refundCaseId,
        String paymentId,
        String referralId,
        String caseType,
        BigDecimal amount,
        String currency,
        String status,
        String outcome,
        String linkedBookingReference,
        LocalDateTime openedOn,
        LocalDateTime closedOn) {
}

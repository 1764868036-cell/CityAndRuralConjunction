package io.camunda.demo.hospital.persistence.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What the funder was asked for and how it decided.
 *
 * <p>The pathway treats the funder as an external party: the worker publishes the request, waits
 * for the answer, and writes both to one row so the audit can show what was asked and what came
 * back.
 *
 * @param authorisationId   the authorisation's own reference, the primary key
 * @param referralId        the referral being funded
 * @param patientId         convenience copy of the referral's patient
 * @param treatmentCycleId  the cycle the money is for, empty when the funder decides per referral
 * @param funderCode        which insurer or funding organisation answered
 * @param requestedAmount   the amount that was asked for
 * @param authorisedAmount  the amount that was granted, empty when the request was declined
 * @param currency          ISO code of both amounts
 * @param status            requested, authorised or declined
 * @param decisionReason    the funder's reason, quoted in letters and in the audit
 * @param requestedOn       when the request went out
 * @param decidedOn         when the answer arrived
 */
public record FundingAuthorisation(
        String authorisationId,
        String referralId,
        String patientId,
        String treatmentCycleId,
        String funderCode,
        BigDecimal requestedAmount,
        BigDecimal authorisedAmount,
        String currency,
        String status,
        String decisionReason,
        LocalDateTime requestedOn,
        LocalDateTime decidedOn) {
}

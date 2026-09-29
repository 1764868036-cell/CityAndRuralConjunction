package io.camunda.demo.hospital.persistence.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The money actually released against an authorisation.
 *
 * <p>Kept apart from the authorisation because the two have different owners and different clocks:
 * the funder approves an amount, the hospital's finance step clears it, and a clearance can expire
 * while the authorisation stays valid.
 *
 * @param clearanceId        the clearance's own reference, the primary key
 * @param authorisationId    the authorisation the money is released against
 * @param referralId         the referral being funded, for queries that do not want to join
 * @param clearanceReference the finance system's reference for the release
 * @param clearedAmount      the amount that was released
 * @param currency           ISO code of the amount
 * @param clearedOn          when the money was released
 * @param validUntil         the last day the release may be drawn on
 * @param status             cleared, drawn or expired
 */
public record FundingClearance(
        String clearanceId,
        String authorisationId,
        String referralId,
        String clearanceReference,
        BigDecimal clearedAmount,
        String currency,
        LocalDateTime clearedOn,
        LocalDate validUntil,
        String status) {
}

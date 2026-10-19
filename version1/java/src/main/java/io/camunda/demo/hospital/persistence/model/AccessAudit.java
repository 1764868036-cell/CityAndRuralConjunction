package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * An access decision of the identity and access segment, kept as the proof of who decided what.
 *
 * <p>Append-only: the aggregate has no update, because an audit row that can be rewritten proves
 * nothing. A later withdrawal of access is a second row with action {@code revoke}.
 *
 * @param auditId          the audit row's own reference, the primary key
 * @param staffUserId      the member of staff the decision is about
 * @param targetPatientId  patient whose record is in question, empty when the decision is not patient-specific
 * @param targetReferralId referral whose record is in question, empty when the decision is not referral-specific
 * @param action           what was decided: grant, revoke or view
 * @param decision         approved or denied
 * @param reason           the reason the decider gave, quoted when the decision is reviewed
 * @param decidedBy        who made the decision
 * @param decidedOn        when the decision was made
 */
public record AccessAudit(
        String auditId,
        String staffUserId,
        String targetPatientId,
        String targetReferralId,
        String action,
        String decision,
        String reason,
        String decidedBy,
        LocalDateTime decidedOn) {
}

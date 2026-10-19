package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Payment;
import io.camunda.demo.hospital.persistence.model.RefundCase;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Process 11 "Refunds and Fund Transfers" plus the refund path that processes 9 and 10 hand over
 * at the end of the pathway.
 *
 * <ul>
 *   <li>{@code send-refund-request} (P11_Task_SendRequest) asks the payment provider for the
 *       refund and publishes {@code Hospital pathway: Receive PSP refund result message}.</li>
 *   <li>{@code record-refund-outcome} (P11_Task_RecordResult) books the result on the patient
 *       account.</li>
 *   <li>{@code link-fund-transfer-records} (P11_Task_LinkRecords) links the money transfer to the
 *       other booking.</li>
 * </ul>
 *
 * <p>A reversal names the charge it reverses, so the request step opens the case against the
 * charge of this referral (the one the provider collected money for) and records the provider's
 * answer on it; the two following steps read that case back - the result step books the amount it
 * finds on record, and the link step writes the booking the money went to. A pathway that reached
 * the refund step without a collected charge (a patient who refuses at consent is refunded before
 * anything is charged) has nothing to reverse, and then there is no case to open.
 */
@Component
public class RefundWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(RefundWorkers.class);

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;

    public RefundWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
    }

    /** P11_Task_SendRequest - ask the provider for the refund of the original booking. */
    @JobWorker(type = "send-refund-request")
    public Map<String, Object> sendRefundRequest(final ActivatedJob job) {
        return askProviderForRefund("refund", job);
    }

    /** P11_Task_RecordResult - record the provider's answer against the patient account. */
    @JobWorker(type = "record-refund-outcome")
    public Map<String, Object> recordRefundOutcome(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String refundStatus = Variables.text(variables, "refundStatus", "completed");
        String refundedAmount = Variables.text(variables, "refundedAmount", "0.00");

        RefundCase recorded = latestCase(referralId).orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundRecorded", true);
        result.put("refundRecordedDate", Optional.ofNullable(recorded).map(RefundCase::closedOn)
                .map(LocalDateTime::toLocalDate).map(LocalDate::toString).orElse(LocalDate.now().toString()));
        result.put("refundRecordedAmount", Optional.ofNullable(recorded).map(RefundCase::amount)
                .map(BigDecimal::toPlainString).orElse(refundedAmount));
        result.put("patientAccountUpdated", true);

        LOG.info("referral {}: refund result '{}' over {} recorded on the patient account (job {})",
                referralId, refundStatus, result.get("refundRecordedAmount"), job.getKey());
        return result;
    }

    /** P11_Task_LinkRecords - link the transfer to the booking that received the money. */
    @JobWorker(type = "link-fund-transfer-records")
    public Map<String, Object> linkFundTransferRecords(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String targetBooking = Variables.text(variables, "transferBookingReference", "BKG-" + referralId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkedBookingReference", targetBooking);
        result.put("fundTransferLinked", true);
        result.put("transferLinkedDate", LocalDate.now().toString());

        latestCase(referralId).ifPresent(refundCase -> {
            database.refundCases().update(new RefundCase(refundCase.refundCaseId(), refundCase.paymentId(),
                    refundCase.referralId(), refundCase.caseType(), refundCase.amount(), refundCase.currency(),
                    refundCase.status(), refundCase.outcome(), targetBooking, refundCase.openedOn(),
                    refundCase.closedOn()));
            LOG.info("referral {}: case {} linked to booking {} (job {})",
                    referralId, refundCase.refundCaseId(), targetBooking, job.getKey());
        });
        return result;
    }

    private Map<String, Object> askProviderForRefund(String purpose, ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        boolean completed = properties.getRefundOutcome() == SimulationProperties.RefundOutcome.COMPLETED;
        String amount = Variables.text(variables, "approvedRefundAmount",
                Variables.text(variables, "refundAmount", Variables.text(variables, "paymentAmount", "1250.00")));

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("refundStatus", completed ? "completed" : "rejected");
        answer.put("refundReference", completed ? "RFD-" + referralId : "");
        answer.put("refundedAmount", completed ? amount : "0.00");
        answer.put("refundProviderNote", "Refund provider answer (simulated)");

        LOG.info("referral {}: sending the {} to the payment service provider, simulating '{}' (job {})",
                referralId, purpose, answer.get("refundStatus"), job.getKey());

        recordRefundCase(job, variables, completed);

        messenger.publish(HospitalMessages.REFUND_RESULT, variables, answer);
        return answer;
    }

    /**
     * Opens the case against the charge of this referral and records the provider's answer.
     *
     * <p>The case type follows the model: a transfer to another booking is a fund transfer, every
     * other reversal is a refund. The charge is the one that collected money; when the pathway
     * reversed no charge on record there is nothing to reverse, so no case is opened. One row per
     * referral and case type ({@code RFD-<referralId>-<caseType>}) carries the whole reversal: a
     * referral that is worked a second time updates that row instead of opening a second case.
     */
    private void recordRefundCase(ActivatedJob job, Map<String, Object> variables, boolean completed) {
        String referralId = referralId(variables);
        String targetBooking = Variables.text(variables, "transferBookingReference", null);
        String caseType = targetBooking == null ? "refund" : "fund-transfer";
        String currency = Variables.text(variables, "currency", "GBP");
        BigDecimal amount = Variables.amount(variables, "approvedRefundAmount",
                Variables.text(variables, "refundAmount", Variables.text(variables, "paymentAmount", "0.00")));
        LocalDateTime now = LocalDateTime.now();

        // Insert or update is decided on the primary key, never on the status: a referral that is
        // worked a second time already has its case, and the closed one from the first pass would be
        // invisible to a status-filtered lookup - the insert would then collide with it.
        String refundCaseId = "RFD-" + referralId + "-" + caseType;
        Optional<RefundCase> onRecord = database.refundCases().findById(refundCaseId);
        Optional<String> chargeId = database.payments().findPaidByReferralId(referralId).map(Payment::paymentId)
                .or(() -> onRecord.map(RefundCase::paymentId));
        if (chargeId.isEmpty()) {
            LOG.warn("referral {}: no collected charge on record, so there is no {} case to open", referralId, caseType);
            return;
        }

        RefundCase refundCase = new RefundCase(
                refundCaseId,
                chargeId.get(),
                referralId, caseType, amount, currency,
                completed ? "closed" : "open",
                completed ? "completed" : "rejected",
                onRecord.map(RefundCase::linkedBookingReference).orElse(targetBooking),
                onRecord.map(RefundCase::openedOn).orElse(now),
                completed ? now : null);

        if (onRecord.isEmpty()) {
            database.refundCases().insert(refundCase);
        } else {
            database.refundCases().update(refundCase);
        }
        LOG.info("referral {}: {} case {} recorded as {} over {} {} (job {})",
                referralId, caseType, refundCase.refundCaseId(), refundCase.outcome(), amount, currency, job.getKey());
    }

    /** The case of this referral that was opened most recently. */
    private Optional<RefundCase> latestCase(String referralId) {
        List<RefundCase> cases = database.refundCases().findByReferralId(referralId);
        return cases.isEmpty() ? Optional.empty() : Optional.of(cases.get(cases.size() - 1));
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }
}

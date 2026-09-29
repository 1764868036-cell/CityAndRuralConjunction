package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.FundingClearance;
import io.camunda.demo.hospital.persistence.model.Payment;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 7 "Process Payment": the hospital asks the payment service provider to collect the
 * patient's share and resolves an unsuccessful payment.
 *
 * <ul>
 *   <li>{@code request-payment} (R7_SendPaymentRequest) sends the secure payment request and
 *       publishes {@code Payment result from provider}.</li>
 *   <li>{@code check-payment-idempotency} (R7_CheckPriorCharge) decides whether the payment may
 *       be attempted again, so the retry loop ends in the investigation path instead of looping
 *       forever.</li>
 *   <li>{@code flag-payment-investigation} (R7_FlagInvestigation) blocks a second charge.</li>
 *   <li>{@code send-payment-confirmation-to-bookings} (P7_Task_SendPaymentConfirmation) tells
 *       Treatment Bookings that the patient has paid, so care can start.</li>
 * </ul>
 *
 * <p>The charge is the row the whole money side hangs on: it is opened when the advance payment is
 * requested, it carries the provider's answer once the provider has answered, and the refund path
 * reverses it later. The idempotency check reads that row - a charge that collected money is the
 * prior charge that must not be collected twice.
 */
@Component
public class PaymentWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorkers.class);

    private static final String RETRY_COUNTER = "paymentRetryCount";

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;

    public PaymentWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
    }

    /** R7_SendPaymentRequest - ask the provider to collect the patient share. */
    @JobWorker(type = "request-payment")
    public Map<String, Object> requestPayment(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String chargeAmount = Variables.text(variables, "chargeAmount", "1250.00");
        String currency = Variables.text(variables, "currency", "GBP");
        int attempt = Variables.nextAttempt(variables, RETRY_COUNTER);
        boolean paid = properties.getPaymentOutcome() == SimulationProperties.PaymentOutcome.PAID;

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put(RETRY_COUNTER, attempt);
        answer.put("paymentStatus", paid ? "paid" : "failed");
        answer.put("paymentReference", paid ? "PAY-" + referralId : "");
        answer.put("paidAmount", paid ? chargeAmount : "0.00");
        answer.put("paymentDate", paid ? LocalDate.now().toString() : "");
        answer.put("currency", currency);
        answer.put("paymentFailureReason", paid ? "" : "Provider reported an unsuccessful payment (simulated)");

        LOG.info("referral {}: provider answered paymentStatus={} on attempt {} (job {})",
                referralId, answer.get("paymentStatus"), attempt, job.getKey());

        recordCharge(referralId, variables, chargeAmount, paid);

        messenger.publish(HospitalMessages.PAYMENT_RESULT, variables, answer);
        return answer;
    }

    /** R7_CheckPriorCharge - is a charge from an earlier attempt already on record? */
    @JobWorker(type = "check-payment-idempotency")
    public Map<String, Object> checkPaymentIdempotency(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        int attempt = Variables.number(variables, RETRY_COUNTER, 0);
        boolean exhausted = attempt > properties.getRetryAttemptsBeforeSuccess();

        Optional<Payment> collected = database.payments().findPaidByReferralId(referralId);
        boolean priorChargeFound = properties.isPriorChargeFound() || exhausted || collected.isPresent();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("priorChargeFound", priorChargeFound);
        result.put("priorChargeReference", collected
                .map(payment -> reference(payment.providerReference(), "TXN-" + referralId))
                .orElse("TXN-" + referralId));
        result.put("paymentAttempts", attempt);

        LOG.info("referral {}: idempotency check after {} attempt(s) -> priorChargeFound={} (job {})",
                referralId, attempt, priorChargeFound, job.getKey());
        return result;
    }

    /** R7_FlagInvestigation - put the case in front of Finance and forbid another charge. */
    @JobWorker(type = "flag-payment-investigation")
    public Map<String, Object> flagPaymentForInvestigation(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("investigationReference", "INV-" + referralId);
        result.put("financeNotified", true);
        result.put("rechargeBlocked", true);

        LOG.info("referral {}: flagged for Finance investigation {}, no further charge attempted (job {})",
                referralId, result.get("investigationReference"), job.getKey());
        return result;
    }

    /** P7_Task_SendPaymentConfirmation - tell Treatment Bookings the patient has paid. */
    @JobWorker(type = "send-payment-confirmation-to-bookings")
    public Map<String, Object> sendPaymentConfirmationToBookings(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Optional<Payment> collected = database.payments().findPaidByReferralId(referralId);
        Optional<FundingClearance> clearance = database.fundingClearances().findDrawableByReferralId(referralId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentConfirmationSent", true);
        result.put("paymentConfirmationReference", "PCF-" + referralId);
        result.put("confirmedPaymentReference", collected
                .map(payment -> reference(payment.providerReference(), "PAY-" + referralId))
                .orElse("PAY-" + referralId));
        result.put("confirmedClearedAmount", clearance.map(FundingClearance::clearedAmount)
                .map(amount -> amount.toPlainString())
                .orElse("0.00"));
        result.put("treatmentBookingsNotified", true);

        LOG.info("referral {}: payment confirmation {} sent to Treatment Bookings (job {})",
                referralId, result.get("paymentConfirmationReference"), job.getKey());
        return result;
    }

    /**
     * Completes the charge of this referral with the provider's answer - one key, one lifecycle.
     *
     * <p>The advance-payment step opened the row ({@code PAY-<referralId>}, status
     * {@code requested}); this step writes the provider's result onto the same row, so a pathway has
     * exactly one charge that the refund and fund-transfer steps reverse. The retry loop updates
     * that same row, which is what makes the attempt and its outcome visible as one history. A row
     * that is not on record (a pathway that reaches process 7 without the advance-payment step) is
     * inserted here. Whether the referral had already collected money is read from the charges and
     * stored on the row, so a repeated attempt is visible after the fact.
     */
    private void recordCharge(String referralId, Map<String, Object> variables, String chargeAmount, boolean paid) {
        String chargeId = "PAY-" + referralId;
        Optional<Payment> onRecord = database.payments().findById(chargeId);
        boolean priorCharge = database.payments().findPaidByReferralId(referralId).isPresent();
        LocalDateTime now = LocalDateTime.now();

        Payment charge = new Payment(
                chargeId, referralId, patientId(variables),
                Variables.text(variables, "invoiceReference", Variables.text(variables, "chargeReference",
                        "CHG-" + referralId)),
                Variables.amount(variables, "chargeAmount", chargeAmount),
                Variables.text(variables, "currency", "GBP"),
                paid ? "paid" : "failed",
                paid ? Variables.text(variables, "paymentReference", "PAY-" + referralId) : null,
                onRecord.map(Payment::priorChargeFound).orElse(priorCharge),
                onRecord.map(Payment::requestedOn).orElse(now),
                paid ? now : null);

        if (onRecord.isEmpty()) {
            database.payments().insert(charge);
        } else {
            database.payments().update(charge);
        }
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }

    /** The reference the record carries, or the pathway's own reference when it has none. */
    private String reference(String recorded, String fallback) {
        return recorded == null || recorded.isBlank() ? fallback : recorded;
    }
}

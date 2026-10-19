package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Enquiry;
import io.camunda.demo.hospital.persistence.model.FundingAuthorisation;
import io.camunda.demo.hospital.persistence.model.FundingClearance;
import io.camunda.demo.hospital.persistence.model.Payment;
import io.camunda.demo.hospital.persistence.model.TreatmentCycle;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
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
 * Process 6 "Verify Cost Allocation Method": the advance-payment and funding-clearance side that
 * sits next to the insurer authorisation of phase 6.
 *
 * <ul>
 *   <li>{@code request-advance-payment} (P6_Task_RequestPayment) requests the required advance
 *       payment from the patient and records it; the model then continues into process 7, where
 *       the payment itself is requested and answered.</li>
 *   <li>{@code send-funding-clearance} (P6_Task_SendFundingClearance) records and sends the funding
 *       clearance when no advance payment is due.</li>
 *   <li>{@code reply-to-finance-enquiry} (P6_Task_SendFinanceReply) returns the Finance response to
 *       Call Handling.</li>
 * </ul>
 *
 * <p>All three steps hand over inside the executable process, so none of them publishes a BPMN
 * message the pathway waits for. What they do is move the money side into the records: the
 * clearance step reads the funding decision of the referral and releases money against it (a
 * referral whose funding needed no external authorisation has the allocation the pathway recorded
 * as its decision), the advance-payment step opens the charge the patient is asked to pay, and the
 * finance reply closes the enquiry it answers.
 */
@Component
public class FundingClearanceWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(FundingClearanceWorkers.class);

    private final HospitalDatabase database;
    private final SimulationProperties properties;

    public FundingClearanceWorkers(HospitalDatabase database, SimulationProperties properties) {
        this.database = database;
        this.properties = properties;
    }

    /** P6_Task_RequestPayment - request the required advance payment from the patient. */
    @JobWorker(type = "request-advance-payment")
    public Map<String, Object> requestAdvancePayment(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String chargeAmount = Variables.text(variables, "chargeAmount",
                Variables.text(variables, "approvedFundingAmount", "1250.00"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentRequired", true);
        result.put("advancePaymentRequested", true);
        result.put("advancePaymentReference", "ADV-" + referralId);
        result.put("advancePaymentAmount", chargeAmount);
        result.put("advancePaymentRequestedDate", LocalDate.now().toString());

        LOG.info("referral {}: advance payment of {} requested from the patient (job {})",
                referralId, chargeAmount, job.getKey());

        recordAdvanceCharge(referralId, variables, chargeAmount);
        return result;
    }

    /** P6_Task_SendFundingClearance - record and send the funding clearance (no advance payment). */
    @JobWorker(type = "send-funding-clearance")
    public Map<String, Object> sendFundingClearance(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentRequired", false);
        result.put("fundingClearanceSent", true);

        FundingClearance clearance = recordClearance(job, variables);
        result.put("fundingClearanceReference", clearance.clearanceReference());
        result.put("fundingClearanceAmount", clearance.clearedAmount().toPlainString());
        result.put("fundingClearanceDate", clearance.clearedOn().toLocalDate().toString());

        LOG.info("referral {}: funding clearance {} over {} {} sent, no advance payment required (job {})",
                referralId, clearance.clearanceReference(), clearance.clearedAmount(), clearance.currency(),
                job.getKey());
        return result;
    }

    /** P6_Task_SendFinanceReply - return the Finance response to Call Handling. */
    @JobWorker(type = "reply-to-finance-enquiry")
    public Map<String, Object> replyToFinanceEnquiry(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("financeResponseSent", true);
        result.put("financeResponseReference", "FIN-RES-" + referralId);
        result.put("financeResponseDate", LocalDate.now().toString());
        result.put("financeResponseSummary", "Finance enquiry answered by the Finance team (simulated).");

        LOG.info("referral {}: finance enquiry response {} returned to Call Handling (job {})",
                referralId, result.get("financeResponseReference"), job.getKey());

        answerOpenEnquiries(job, variables);
        return result;
    }

    /**
     * Opens the charge the advance payment asks for, one row per referral.
     *
     * <p>The row exists before the money moves on purpose: it is the charge process 7 completes
     * with the provider's result, and it is also the charge a later refund reverses - one key, one
     * lifecycle. Whether the referral already has a charge on record is read from the charges
     * themselves and stored on the row, so a repeated attempt is visible without replaying the
     * pathway.
     */
    private void recordAdvanceCharge(String referralId, Map<String, Object> variables, String chargeAmount) {
        String chargeId = "PAY-" + referralId;
        Optional<Payment> onRecord = database.payments().findById(chargeId);
        boolean priorCharge = onRecord.map(Payment::priorChargeFound)
                .orElseGet(() -> !database.payments().findByReferralId(referralId).isEmpty());

        Payment charge = new Payment(
                chargeId, referralId, patientId(variables),
                Variables.text(variables, "advancePaymentReference", "ADV-" + referralId),
                Variables.amount(variables, "chargeAmount", chargeAmount),
                Variables.text(variables, "currency", "GBP"),
                "requested", null, priorCharge,
                onRecord.map(Payment::requestedOn).orElseGet(LocalDateTime::now), null);

        if (onRecord.isEmpty()) {
            database.payments().insert(charge);
        } else {
            database.payments().update(charge);
        }
    }

    /**
     * Releases the money and records the release against the funding decision of this referral.
     *
     * <p>The decision is the funder's authorisation when there is one. A referral whose funding
     * needed no external authorisation (R6_AuthNeeded answered "no") has the allocation the
     * pathway recorded itself - payer, reference, amount and limits are the funding decision of
     * that referral - so it is written here before the clearance points at it.
     */
    private FundingClearance recordClearance(ActivatedJob job, Map<String, Object> variables) {
        String referralId = referralId(variables);
        String currency = Variables.text(variables, "currency", "GBP");
        LocalDateTime now = LocalDateTime.now();

        List<FundingAuthorisation> decided = database.fundingAuthorisations().findByReferralId(referralId);
        FundingAuthorisation authorisation = decided.isEmpty()
                ? recordAllocation(referralId, variables)
                : decided.get(decided.size() - 1);

        String clearanceId = "CLR-" + referralId;
        Optional<FundingClearance> onRecord = database.fundingClearances().findById(clearanceId);
        FundingClearance clearance = new FundingClearance(
                clearanceId, authorisation.authorisationId(), referralId,
                Variables.text(variables, "clearanceReference", "CLR-" + referralId),
                Variables.amount(variables, "approvedFundingAmount", Variables.text(variables, "chargeAmount", "0.00")),
                currency,
                onRecord.map(FundingClearance::clearedOn).orElse(now),
                Variables.date(variables, "fundingFollowUpDate", null),
                "cleared");

        if (onRecord.isEmpty()) {
            database.fundingClearances().insert(clearance);
        } else {
            database.fundingClearances().update(clearance);
        }
        LOG.info("referral {}: {} released against authorisation {} (job {})",
                referralId, clearance.clearedAmount(), authorisation.authorisationId(), job.getKey());
        return clearance;
    }

    /** Writes the funding decision of a referral that needed no external authorisation. */
    private FundingAuthorisation recordAllocation(String referralId, Map<String, Object> variables) {
        String authorisationId = "FUND-" + referralId;
        Optional<FundingAuthorisation> onRecord = database.fundingAuthorisations().findById(authorisationId);
        if (onRecord.isPresent()) {
            return onRecord.get();
        }

        TreatmentCycle cycle = database.treatmentCycles().findByReferralId(referralId).stream()
                .reduce((first, second) -> second)
                .orElse(null);
        boolean approved = Variables.flag(variables, "fundingApproved", true);
        LocalDateTime now = LocalDateTime.now();

        FundingAuthorisation allocation = new FundingAuthorisation(
                authorisationId, referralId, patientId(variables),
                cycle == null ? null : cycle.treatmentCycleId(),
                Variables.text(variables, "payerName", Variables.text(variables, "payerType", "funder of " + referralId)),
                Variables.amount(variables, "estimatedCost", "0.00"),
                approved ? Variables.amount(variables, "approvedFundingAmount", "0.00") : null,
                Variables.text(variables, "currency", "GBP"),
                approved ? "authorised" : "declined",
                Variables.text(variables, "fundingDecisionReason",
                        "Funding allocated by the pathway; no external authorisation was required"),
                now, now);

        database.fundingAuthorisations().insert(allocation);
        LOG.info("referral {}: funding allocation {} recorded as the decision of this referral",
                referralId, authorisationId);
        return allocation;
    }

    /** Closes the finance enquiry of this referral with the answer of the Finance team. */
    private void answerOpenEnquiries(ActivatedJob job, Map<String, Object> variables) {
        String referralId = referralId(variables);
        String question = Variables.text(variables, "financeEnquirySummary",
                "Would the patient like an estimate of the remaining treatment costs?");
        String answer = Variables.text(variables, "financeEnquiryResponse",
                "Finance answered the payment or funding enquiry (simulated).");

        Optional<Enquiry> open = database.enquiries().findById("ENQ-FIN-" + referralId);
        open.filter(enquiry -> "open".equals(enquiry.status()))
                .ifPresent(enquiry -> {
                    database.enquiries().update(new Enquiry(enquiry.enquiryId(), enquiry.patientId(),
                            enquiry.referralId(), enquiry.channel(), enquiry.raisedBy(), enquiry.contactDetails(),
                            enquiry.question() == null ? question : enquiry.question(),
                            answer, "answered", enquiry.receivedOn(), LocalDateTime.now()));
                    LOG.info("referral {}: finance enquiry {} answered (job {})",
                            referralId, enquiry.enquiryId(), job.getKey());
                });
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}

package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.FundingAuthorisation;
import io.camunda.demo.hospital.persistence.model.TreatmentCycle;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 6 "Verify Funding and Costs": the hospital asks the insurer or funding organisation for
 * authorisation. {@code request-funding-authorisation} (R6_RequestAuth) publishes
 * {@code Funding authorisation decision}; the gateway R6_AuthApproved reads the variable
 * {@code fundingAuthorised} from that message.
 *
 * <p>The step also writes the request and the decision into the system of record, because a
 * declined request has to stay visible with its reason and because the clearance step later
 * releases money against exactly this authorisation. Request and decision share one row: the worker
 * asks and records the answer in the same activation, so the row is inserted once and updated when
 * the step is replayed.
 */
@Component
public class FundingWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(FundingWorkers.class);

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;

    public FundingWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
    }

    /** R6_RequestAuth - request authorisation from the insurer or funding organisation. */
    @JobWorker(type = "request-funding-authorisation")
    public Map<String, Object> requestFundingAuthorisation(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String estimatedCost = Variables.text(variables, "estimatedCost", "1250.00");
        String currency = Variables.text(variables, "currency", "GBP");
        boolean authorised = properties.isFundingAuthorised();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("fundingAuthorised", authorised);
        answer.put("fundingReference", authorised ? "FUND-" + referralId : "");
        answer.put("approvedFundingAmount", authorised ? estimatedCost : "0.00");
        answer.put("fundingLimits", authorised
                ? "Plan limit " + currency + " 2500.00; per-cycle limit " + currency + " 1250.00"
                : "No authorisation granted (simulated)");
        answer.put("currency", currency);
        answer.put("funderDecisionReason", authorised ? "Authorised (simulated)" : "Declined (simulated)");

        LOG.info("referral {}: funding organisation answered authorised={} (job {})", referralId, authorised, job.getKey());

        recordAuthorisation(job, variables);

        messenger.publish(HospitalMessages.FUNDER_DECISION, variables, answer);
        return answer;
    }

    /** Records what the funder was asked for and what it decided, in one row per referral. */
    private void recordAuthorisation(ActivatedJob job, Map<String, Object> variables) {
        String referralId = referralId(variables);
        String authorisationId = "FUND-" + referralId;
        String currency = Variables.text(variables, "currency", "GBP");
        boolean authorised = properties.isFundingAuthorised();
        LocalDateTime now = LocalDateTime.now();

        Optional<FundingAuthorisation> onRecord = database.fundingAuthorisations().findById(authorisationId);
        List<TreatmentCycle> cycles = database.treatmentCycles().findByReferralId(referralId);

        FundingAuthorisation authorisation = new FundingAuthorisation(
                authorisationId, referralId, patientId(variables),
                cycles.isEmpty() ? null : cycles.get(cycles.size() - 1).treatmentCycleId(),
                Variables.text(variables, "payerName", Variables.text(variables, "payerType", "funder of " + referralId)),
                Variables.amount(variables, "estimatedCost", "1250.00"),
                authorised ? Variables.amount(variables, "approvedFundingAmount", Variables.text(variables, "estimatedCost", "1250.00")) : null,
                currency,
                authorised ? "authorised" : "declined",
                Variables.text(variables, "funderDecisionReason", "Funder decision (simulated)"),
                onRecord.map(FundingAuthorisation::requestedOn).orElse(now),
                now);

        if (onRecord.isEmpty()) {
            database.fundingAuthorisations().insert(authorisation);
        } else {
            database.fundingAuthorisations().update(authorisation);
        }

        LOG.info("referral {}: funding authorisation {} recorded as {} over {} {} (job {})",
                referralId, authorisationId, authorisation.status(), authorisation.authorisedAmount(), currency,
                job.getKey());
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}

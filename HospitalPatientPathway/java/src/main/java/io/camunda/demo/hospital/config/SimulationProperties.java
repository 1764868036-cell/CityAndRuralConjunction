package io.camunda.demo.hospital.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Content of the simulated answers of the external parties and of the hospital-side decisions
 * the model leaves to a person. Pure simulation: the switches never change the BPMN model, they
 * only change the values a worker returns or publishes, so the same model can be driven down
 * every branch.
 */
@Component
@ConfigurationProperties(prefix = "hospital.simulation")
public class SimulationProperties {

    public enum PatientDecision {
        /** Patient accepts the offered appointment. */
        ACCEPT,
        /** Patient proposes another date. */
        ALTERNATIVE,
        /** Patient declines the consultation. */
        DECLINE
    }

    public enum PaymentOutcome {
        /** Payment provider confirms the payment. */
        PAID,
        /** Payment provider reports a failed payment. */
        FAILED
    }

    public enum RefundOutcome {
        /** Payment provider reports the refund as completed. */
        COMPLETED,
        /** Payment provider rejects the refund. */
        REJECTED
    }

    private boolean autoResponderEnabled = true;
    private Duration autoResponderInterval = Duration.ofSeconds(10);
    private String slotAvailability = "available";
    private PatientDecision patientDecision = PatientDecision.ACCEPT;
    private String patientConsentDecision = "consent";
    private int retryAttemptsBeforeSuccess = 1;
    private boolean treatmentServicesAvailable = true;
    private boolean fundingAuthorised = true;
    private PaymentOutcome paymentOutcome = PaymentOutcome.PAID;
    private boolean priorChargeFound = false;
    private RefundOutcome refundOutcome = RefundOutcome.COMPLETED;
    private boolean testResultsFitToContinue = true;
    private String defaultReferralId = "REF-1001";
    private String defaultPatientId = "PAT-1001";
    private String defaultPatientName = "Alex Morgan";
    private String defaultIncidentReference = "INC-1001";
    private String scenario = "none";

    public boolean isAutoResponderEnabled() {
        return autoResponderEnabled;
    }

    public void setAutoResponderEnabled(boolean autoResponderEnabled) {
        this.autoResponderEnabled = autoResponderEnabled;
    }

    public Duration getAutoResponderInterval() {
        return autoResponderInterval;
    }

    public void setAutoResponderInterval(Duration autoResponderInterval) {
        this.autoResponderInterval = autoResponderInterval;
    }

    public String getSlotAvailability() {
        return slotAvailability;
    }

    public void setSlotAvailability(String slotAvailability) {
        this.slotAvailability = slotAvailability;
    }

    public PatientDecision getPatientDecision() {
        return patientDecision;
    }

    public void setPatientDecision(PatientDecision patientDecision) {
        this.patientDecision = patientDecision;
    }

    public String getPatientConsentDecision() {
        return patientConsentDecision;
    }

    public void setPatientConsentDecision(String patientConsentDecision) {
        this.patientConsentDecision = patientConsentDecision;
    }

    public int getRetryAttemptsBeforeSuccess() {
        return retryAttemptsBeforeSuccess;
    }

    public void setRetryAttemptsBeforeSuccess(int retryAttemptsBeforeSuccess) {
        this.retryAttemptsBeforeSuccess = retryAttemptsBeforeSuccess;
    }

    public boolean isTreatmentServicesAvailable() {
        return treatmentServicesAvailable;
    }

    public void setTreatmentServicesAvailable(boolean treatmentServicesAvailable) {
        this.treatmentServicesAvailable = treatmentServicesAvailable;
    }

    public boolean isFundingAuthorised() {
        return fundingAuthorised;
    }

    public void setFundingAuthorised(boolean fundingAuthorised) {
        this.fundingAuthorised = fundingAuthorised;
    }

    public PaymentOutcome getPaymentOutcome() {
        return paymentOutcome;
    }

    public void setPaymentOutcome(PaymentOutcome paymentOutcome) {
        this.paymentOutcome = paymentOutcome;
    }

    public boolean isPriorChargeFound() {
        return priorChargeFound;
    }

    public void setPriorChargeFound(boolean priorChargeFound) {
        this.priorChargeFound = priorChargeFound;
    }

    public RefundOutcome getRefundOutcome() {
        return refundOutcome;
    }

    public void setRefundOutcome(RefundOutcome refundOutcome) {
        this.refundOutcome = refundOutcome;
    }

    public boolean isTestResultsFitToContinue() {
        return testResultsFitToContinue;
    }

    public void setTestResultsFitToContinue(boolean testResultsFitToContinue) {
        this.testResultsFitToContinue = testResultsFitToContinue;
    }

    public String getDefaultReferralId() {
        return defaultReferralId;
    }

    public void setDefaultReferralId(String defaultReferralId) {
        this.defaultReferralId = defaultReferralId;
    }

    public String getDefaultPatientId() {
        return defaultPatientId;
    }

    public void setDefaultPatientId(String defaultPatientId) {
        this.defaultPatientId = defaultPatientId;
    }

    public String getDefaultPatientName() {
        return defaultPatientName;
    }

    public void setDefaultPatientName(String defaultPatientName) {
        this.defaultPatientName = defaultPatientName;
    }

    public String getDefaultIncidentReference() {
        return defaultIncidentReference;
    }

    public void setDefaultIncidentReference(String defaultIncidentReference) {
        this.defaultIncidentReference = defaultIncidentReference;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }
}

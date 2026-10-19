package io.camunda.demo.hospital.simulation;

import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.client.CamundaClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Answers the pathway on behalf of people who cannot be modelled as a job worker step.
 *
 * <p>Four points of the merged model wait for somebody who is not triggered by a hospital task:
 * the patient's appointment preferences (phase 3), the patient's consent decision (phase 4), the
 * laboratory results of the next treatment cycle (phase 8) and the "service restored" message of
 * process 16. This component publishes them on a fixed interval.
 *
 * <p>The identifiers come from the configuration ({@code hospital.simulation.default-referral-id}
 * and {@code default-patient-id}); a real integration would read them from the running instance.
 * Messages that nobody is waiting for are buffered by TTL and simply expire, so the loop is safe
 * to leave running.
 *
 * <p>Switched on by default (a demo should need no hand-published messages); turn it off with
 * {@code hospital.simulation.auto-responder-enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "hospital.simulation", name = "auto-responder-enabled", havingValue = "true")
public class PatientAutoResponder {

    private static final Logger LOG = LoggerFactory.getLogger(PatientAutoResponder.class);

    private final CamundaClient camundaClient;
    private final SimulationProperties properties;

    public PatientAutoResponder(CamundaClient camundaClient, SimulationProperties properties) {
        this.camundaClient = camundaClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${hospital.simulation.auto-responder-interval:10s}")
    public void answerWaitingSteps() {
        String referralId = properties.getDefaultReferralId();
        String patientId = properties.getDefaultPatientId();

        publish(HospitalMessages.PATIENT_PREFERENCE, referralId, Map.of(
                "preferredClinician", "no preference",
                "contactChannel", "telephone"));
        publish(HospitalMessages.PATIENT_CONSENT_DECISION, referralId, Map.of(
                "informedConsent", !"refuse".equalsIgnoreCase(properties.getPatientConsentDecision()),
                "consentNotes", "Patient consent decision (simulated)"));
        publish(HospitalMessages.TEST_RESULTS, patientId, Map.of(
                "fitToContinue", properties.isTestResultsFitToContinue() ? "yes" : "no",
                "bloodTestSummary", "Blood test results (simulated)"));
        publish(HospitalMessages.SERVICE_RESTORED, properties.getDefaultIncidentReference(), Map.of(
                "restorationSummary", "Service available again (simulated)"));
    }

    private void publish(String messageName, String correlationValue, Map<String, Object> payload) {
        String keyVariable = HospitalMessages.correlationKeyVariable(messageName);
        String correlationKey = correlationValue == null ? "(none)" : correlationValue;
        try {
            var namedMessage = camundaClient.newPublishMessageCommand().messageName(messageName);
            var message = keyVariable == null
                    ? namedMessage.withoutCorrelationKey()
                    : namedMessage.correlationKey(correlationKey);
            message
                    .variables(new LinkedHashMap<>(payload))
                    .timeToLive(Duration.ofMinutes(1))
                    .send()
                    .join();
            LOG.debug("auto-responder published '{}' (correlationKey={})", messageName, correlationKey);
        } catch (Exception e) {
            LOG.debug("auto-responder could not publish '{}': {}", messageName, e.getMessage());
        }
    }
}
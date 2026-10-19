package io.camunda.demo.hospital.support;

import io.camunda.client.CamundaClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes a BPMN message, i.e. plays the part of an external party (referring organisation,
 * scheduling service, laboratory, insurer, payment provider) or of the patient.
 *
 * <p>Two cases have to be told apart, and the model decides which one applies:
 *
 * <ul>
 *   <li><b>Entry events</b> ({@link HospitalMessages#isStartMessage(String)}) start an instance.
 *       Their subscription exists from the moment the model is deployed, so the message is
 *       published plainly and correlates immediately.</li>
 *   <li><b>Answers to a hospital request</b> - for example the scheduling service answering
 *       {@code request-consultation-slot}. The pathway always sends its request <em>before</em> it
 *       reaches the receive task that waits for the answer, so the answer is published with a time
 *       to live: the engine buffers it and correlates it the moment the subscription is created.
 *       A message nobody waits for expires silently, which keeps the simulation safe when a branch
 *       is not taken.</li>
 * </ul>
 */
@Component
public class ExternalPartyMessenger {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalPartyMessenger.class);

    /** How long an answer waits for the process step that asked for it. */
    private static final Duration MESSAGE_TIME_TO_LIVE = Duration.ofMinutes(15);

    private final CamundaClient camundaClient;

    public ExternalPartyMessenger(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /**
     * Publishes {@code messageName} with the correlation key taken from the variable the model
     * defines for that message.
     *
     * @param variables process variables of the sending step; also used to resolve the
     *     correlation key
     * @param payload variables the receiving step should get
     */
    public void publish(String messageName, Map<String, Object> variables, Map<String, Object> payload) {
        Map<String, Object> messageVariables = payload == null ? Map.of() : payload;
        String correlationKeyVariable = HospitalMessages.correlationKeyVariable(messageName);
        String correlationKey = correlationKeyVariable == null
                ? null
                : Variables.text(variables, correlationKeyVariable);

        if (correlationKeyVariable != null && correlationKey == null) {
            LOG.warn("message '{}' is correlated on '{}' but the sending step did not set it", messageName, correlationKeyVariable);
        }

        try {
            var namedMessage = camundaClient.newPublishMessageCommand().messageName(messageName);
            var message = correlationKey == null
                    ? namedMessage.withoutCorrelationKey()
                    : namedMessage.correlationKey(correlationKey);
            var prepared = message.variables(messageVariables);
            if (HospitalMessages.isStartMessage(messageName)) {
                prepared.send().join();
            } else {
                prepared.timeToLive(MESSAGE_TIME_TO_LIVE).send().join();
            }

            LOG.info("external party answered '{}' (correlationKey={})", messageName,
                    correlationKey == null ? "<none>" : correlationKey);
        } catch (Exception e) {
            LOG.warn("could not publish '{}' (correlationKey={}): {}", messageName, correlationKey, e.getMessage());
        }
    }

    /**
     * Starts an instance of {@code processId} at its plain start event.
     *
     * <p>The pathway has exactly one plain start event: {@code Start_Tasklist} in process 1
     * ("Receive Referral" - the Medical Secretaries take the referral into the hospital system,
     * case study step 1.1). Every other start event is triggered by a message. This is the entry a
     * "Start instance" in Operate or Tasklist uses, and what the {@code referral-tasklist} scenario
     * of this project plays.
     */
    public void startInstance(String processId, Map<String, Object> variables) {
        try {
            var response = camundaClient.newCreateInstanceCommand()
                    .bpmnProcessId(processId)
                    .latestVersion()
                    .variables(variables)
                    .send()
                    .join();
            LOG.info("started instance of '{}' at its plain start event: processInstanceKey={}",
                    processId, response.getProcessInstanceKey());
        } catch (Exception e) {
            LOG.warn("could not start an instance of '{}': {}", processId, e.getMessage());
        }
    }
}

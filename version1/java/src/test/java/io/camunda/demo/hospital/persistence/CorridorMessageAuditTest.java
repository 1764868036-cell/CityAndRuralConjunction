package io.camunda.demo.hospital.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.camunda.demo.hospital.simulation.ExternalEventService;
import io.camunda.demo.hospital.simulation.ScenarioRunner;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.PathwayAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves that the audit trail never gates a message that arrives from outside the pathway.
 *
 * <p>The context starts with the demo's own switches: the {@code record-update} scenario publishes
 * an entry message as soon as the application starts, for a referral the hospital does not have on
 * record ({@code hospital.simulation.default-referral-id}). Until the repair the trail was written
 * first and its foreign key to {@code referral} rejected the subject, so the scenario failed - and
 * with it the application start-up, because the scenario is an {@code ApplicationRunner}.
 *
 * <p>The two tests below cover both halves of that contract: the entry message of an unknown
 * referral is published and recorded, and a trail that is unavailable cannot stop the publication
 * (the message leaves the hospital before it is written down).
 */
@SpringBootTest(properties = {
        "hospital.simulation.scenario=record-update",
        "hospital.simulation.default-referral-id=REF-NOT-ON-RECORD"})
@DisplayName("A corridor message for a referral that is not on record")
class CorridorMessageAuditTest {

    private static final String UNKNOWN_REFERRAL = "REF-NOT-ON-RECORD";

    @Autowired
    private HospitalDatabase database;

    @Autowired
    private ExternalEventService events;

    @Autowired
    private ScenarioRunner scenarioRunner;

    /** The trail is spied on: one test needs it to fail, the others need the real behaviour. */
    @MockitoSpyBean
    private PathwayAudit audit;

    @MockitoBean
    private ExternalPartyMessenger messenger;

    @Test
    @DisplayName("is published and recorded instead of failing the start-up scenario")
    void theRecordUpdateScenarioSurvivesAnUnknownReferral() {
        clearInvocations(messenger, audit);

        scenarioRunner.run(new DefaultApplicationArguments());

        verify(messenger).publish(eq(HospitalMessages.PATHWAY_RECORD_UPDATED), anyMap(), anyMap());
        assertThat(database.referrals().findById(UNKNOWN_REFERRAL))
                .as("the pathway does not know this referral, which is exactly the reported event")
                .isEmpty();
        assertThat(database.eventLog().findByReferralId(UNKNOWN_REFERRAL))
                .as("the trail records the entry message together with the subject it names")
                .isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.eventType()).isEqualTo("message-received");
                    assertThat(event.messageName()).isEqualTo(HospitalMessages.PATHWAY_RECORD_UPDATED);
                });
    }

    @Test
    @DisplayName("is published even when the audit trail cannot be written")
    void aFailingAuditTrailDoesNotStopTheCorridorMessage() {
        clearInvocations(messenger);
        doThrow(new IllegalStateException("the audit trail is unavailable"))
                .when(audit).messageReceived(anyString(), anyMap());

        events.requestMonitoringRun(null);

        verify(messenger).publish(eq(HospitalMessages.MONITORING_REQUEST), anyMap(), anyMap());
    }
}

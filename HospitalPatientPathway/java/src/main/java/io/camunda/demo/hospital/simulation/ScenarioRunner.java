package io.camunda.demo.hospital.simulation;

import io.camunda.demo.hospital.config.SimulationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fires one event of the outside world when the application starts, so a demo can be driven with a
 * single property instead of a running Tasklist session.
 *
 * <p>Set {@code hospital.simulation.scenario} to one of the values in {@link #run} to start that
 * path of the pathway, for example {@code monitoring}, {@code identity} or {@code referral}. The
 * default {@code none} means the worker starts and waits for real work.
 */
@Component
public class ScenarioRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioRunner.class);

    private final ExternalEventService events;
    private final SimulationProperties properties;

    public ScenarioRunner(ExternalEventService events, SimulationProperties properties) {
        this.events = events;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String scenario = properties.getScenario() == null ? "none" : properties.getScenario().trim().toLowerCase();
        switch (scenario) {
            case "none" -> LOG.info("no demo scenario configured; the workers wait for real work");
            case "referral" -> events.submitReferral(null, null);
            case "referral-tasklist" -> events.startReferralFromTasklist(null, null);
            case "clinic-visit" -> events.completeClinicVisit(null, null);
            case "finance-enquiry" -> events.reportFinanceEnquiry(null, null);
            case "cycle-due" -> events.notifyTreatmentCycleDue(null, null);
            case "patient-arrives" -> events.reportPatientAttendsCycle(null, null);
            case "treatment-change" -> events.requestTreatmentChange(null, null);
            case "cancellation" -> events.reportCancellation(null, null);
            case "enquiry" -> events.askPatientQuestion(null, null);
            case "monitoring" -> events.requestMonitoringRun(null);
            case "record-update" -> events.reportPathwayRecordUpdate(null);
            case "identity" -> events.requestAccessChange(null);
            case "interruption" -> events.reportSystemInterruption(null);
            case "appointment-change" -> events.requestAppointmentChange(null, null);
            default -> LOG.warn("scenario '{}' is not known; expected one of referral, referral-tasklist, clinic-visit, "
                    + "finance-enquiry, cycle-due, patient-arrives, treatment-change, cancellation, enquiry, "
                    + "monitoring, record-update, identity, interruption, appointment-change", scenario);
        }
    }
}

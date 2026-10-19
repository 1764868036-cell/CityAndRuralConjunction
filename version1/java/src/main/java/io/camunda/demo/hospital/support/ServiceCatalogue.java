package io.camunda.demo.hospital.support;

import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.TreatmentService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The hospital's service catalogue, resolved the way the pathway names a service.
 *
 * <p>The model selects a service as free text - {@code selectedService}, the "Selected clinic /
 * service" field the referral review writes (R2_Route) - while the slot request, the appointment
 * and the treatment cycle point at a catalogue row by code. This component closes that gap: it
 * looks the selection up by code and then by name, and records a service the hospital does not
 * know yet with the facts the pathway carries for it.
 *
 * <p>Recording on first use is what keeps the pathway self-contained: a referral can name a clinic
 * the catalogue was never seeded with, and the booking steps still have exactly one catalogue row
 * to point at instead of failing on a foreign key. The history is a pass count and a lead time (the
 * days between the booking request and the offered date), which is what the later steps read back
 * when they plan the treatment.
 */
@Component
public class ServiceCatalogue {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceCatalogue.class);

    /** Selection variable of the model, written by the referral review (R2_Route). */
    public static final String SELECTED_SERVICE = "selectedService";

    private static final String CODE_PREFIX = "SVC-";

    /** {@code treatment_service.service_code} is a VARCHAR(32); the code keeps room for a suffix. */
    private static final int CODE_LENGTH = 32;
    private static final int SLUG_LENGTH = CODE_LENGTH - CODE_PREFIX.length() - 2;

    private final HospitalDatabase database;

    public ServiceCatalogue(HospitalDatabase database) {
        this.database = database;
    }

    /**
     * The catalogue code of the service the pathway selected, recording the service on first use.
     *
     * <p>Callers may pass the code or the name in {@code selectedService}: the seeded catalogue is
     * found either way, so a referral that names "Cardiology assessment" and one that names
     * "SVC-CARD-ASSESS" book the same service.
     */
    public String codeOf(Map<String, Object> variables) {
        String selected = Variables.text(variables, SELECTED_SERVICE, "Clinic service");

        Optional<TreatmentService> byCode = database.treatmentServices().findById(selected);
        if (byCode.isPresent()) {
            return byCode.get().serviceCode();
        }
        for (TreatmentService known : database.treatmentServices().findAll()) {
            if (known.serviceName().equalsIgnoreCase(selected)) {
                return known.serviceCode();
            }
        }
        return record(selected, variables);
    }

    /**
     * The catalogue entry of a service, as the booking steps read its defaults.
     *
     * @throws IllegalStateException when the code is not in the catalogue - the steps that plan a
     *     treatment read their pass count and lead time from here, so a missing entry is a defect
     *     of the step that should have recorded the service, not a value to guess
     */
    public TreatmentService entry(String serviceCode) {
        return database.treatmentServices().findById(serviceCode)
                .orElseThrow(() -> new IllegalStateException("the service catalogue has no service " + serviceCode));
    }

    /** Records the selected service with the facts this pathway carries for it. */
    private String record(String selected, Map<String, Object> variables) {
        String code = freeCode(selected);
        TreatmentService service = new TreatmentService(code, selected,
                Variables.text(variables, "treatmentType", "External service"),
                Variables.number(variables, "cycleNumber", 1),
                leadTimeDays(variables), true);
        database.treatmentServices().insert(service);

        LOG.info("service '{}' recorded in the catalogue as {} - the pathway books it for the first time",
                selected, code);
        return code;
    }

    /** A free catalogue code: the slug of the selected service, suffixed while that code is taken. */
    private String freeCode(String selected) {
        String slug = selected.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        String head = slug.isEmpty() ? "SERVICE" : slug;
        String base = CODE_PREFIX + head.substring(0, Math.min(head.length(), SLUG_LENGTH));

        String code = base;
        for (int suffix = 2; database.treatmentServices().existsById(code); suffix++) {
            String tail = "-" + suffix;
            code = base.substring(0, Math.min(base.length(), CODE_LENGTH - tail.length())) + tail;
        }
        return code;
    }

    /** Whole days between the booking request and the offered appointment; zero before one is offered. */
    private int leadTimeDays(Map<String, Object> variables) {
        LocalDate offered = Variables.date(variables, "date_of_appointment", null);
        return offered == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), offered));
    }
}

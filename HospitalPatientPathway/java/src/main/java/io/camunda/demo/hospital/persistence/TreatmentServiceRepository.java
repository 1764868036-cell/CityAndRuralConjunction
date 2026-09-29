package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.TreatmentService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The hospital's service catalogue (table {@code treatment_service}).
 *
 * <p>Seeded reference data, not a business object: the slot and treatment steps read the cycle
 * count and the lead time from here instead of carrying them in the worker code, so the catalogue
 * can be corrected without a rebuild.
 */
@Component
public class TreatmentServiceRepository extends JdbcRepository<TreatmentService, String> {

    TreatmentServiceRepository(JdbcClient jdbc) {
        super(jdbc, "treatment_service", "service_code");
    }

    /** Adds a service to the catalogue. */
    public void insert(TreatmentService service) {
        jdbc().sql("""
                INSERT INTO treatment_service (service_code, service_name, category, default_cycles,
                                               lead_time_days, active)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(service.serviceCode(), service.serviceName(), service.category(),
                        service.defaultCycles(), service.leadTimeDays(), service.active())
                .update();
    }

    /** Overwrites the description and the defaults of an existing service. */
    public void update(TreatmentService service) {
        jdbc().sql("""
                UPDATE treatment_service
                   SET service_name = ?, category = ?, default_cycles = ?, lead_time_days = ?, active = ?
                 WHERE service_code = ?
                """)
                .params(service.serviceName(), service.category(), service.defaultCycles(),
                        service.leadTimeDays(), service.active(), service.serviceCode())
                .update();
    }

    /** The services of one category, as the slot step filters them. */
    public List<TreatmentService> findByCategory(String category) {
        return jdbc().sql("SELECT * FROM treatment_service WHERE category = ? ORDER BY service_code")
                .param(category)
                .query(rows())
                .list();
    }

    /** The services the hospital still delivers. */
    public List<TreatmentService> findActive() {
        return jdbc().sql("SELECT * FROM treatment_service WHERE active = TRUE ORDER BY service_code")
                .query(rows())
                .list();
    }

    @Override
    protected TreatmentService mapRow(ResultSet row, int index) throws SQLException {
        return new TreatmentService(
                row.getString("service_code"),
                row.getString("service_name"),
                row.getString("category"),
                row.getInt("default_cycles"),
                row.getInt("lead_time_days"),
                row.getBoolean("active"));
    }
}

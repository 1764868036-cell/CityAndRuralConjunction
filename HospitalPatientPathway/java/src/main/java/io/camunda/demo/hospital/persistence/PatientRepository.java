package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.Patient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The patients of the pathway (table {@code patient}).
 *
 * <p>The identifier is the pathway's own patient reference, so the registering worker can write the
 * record with the value the model already carries instead of inventing a database key.
 */
@Component
public class PatientRepository extends JdbcRepository<Patient, String> {

    PatientRepository(JdbcClient jdbc) {
        super(jdbc, "patient", "patient_id");
    }

    /** Writes the demographic record of a patient the pathway has just registered. */
    public void insert(Patient patient) {
        jdbc().sql("""
                INSERT INTO patient (patient_id, nhs_number, family_name, given_name, date_of_birth, sex,
                                     contact_email, contact_phone, interpreter_required, registered_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(patient.patientId(), patient.nhsNumber(), patient.familyName(), patient.givenName(),
                        patient.dateOfBirth(), patient.sex(), patient.contactEmail(), patient.contactPhone(),
                        patient.interpreterRequired(), patient.registeredOn())
                .update();
    }

    /** Overwrites the demographics of an existing patient; the reference and the registration date stay. */
    public void update(Patient patient) {
        jdbc().sql("""
                UPDATE patient
                   SET nhs_number = ?, family_name = ?, given_name = ?, date_of_birth = ?, sex = ?,
                       contact_email = ?, contact_phone = ?, interpreter_required = ?
                 WHERE patient_id = ?
                """)
                .params(patient.nhsNumber(), patient.familyName(), patient.givenName(), patient.dateOfBirth(),
                        patient.sex(), patient.contactEmail(), patient.contactPhone(),
                        patient.interpreterRequired(), patient.patientId())
                .update();
    }

    /** The patient with this NHS number; the number is unique in the table. */
    public Optional<Patient> findByNhsNumber(String nhsNumber) {
        return jdbc().sql("SELECT * FROM patient WHERE nhs_number = ?")
                .param(nhsNumber)
                .query(rows())
                .optional();
    }

    @Override
    protected Patient mapRow(ResultSet row, int index) throws SQLException {
        return new Patient(
                row.getString("patient_id"),
                row.getString("nhs_number"),
                row.getString("family_name"),
                row.getString("given_name"),
                row.getObject("date_of_birth", LocalDate.class),
                row.getString("sex"),
                row.getString("contact_email"),
                row.getString("contact_phone"),
                row.getBoolean("interpreter_required"),
                row.getObject("registered_on", LocalDateTime.class));
    }
}

package io.camunda.demo.hospital.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.camunda.demo.hospital.persistence.model.AccessAudit;
import io.camunda.demo.hospital.persistence.model.Appointment;
import io.camunda.demo.hospital.persistence.model.ClinicLetter;
import io.camunda.demo.hospital.persistence.model.Consultation;
import io.camunda.demo.hospital.persistence.model.Enquiry;
import io.camunda.demo.hospital.persistence.model.EventLog;
import io.camunda.demo.hospital.persistence.model.FundingAuthorisation;
import io.camunda.demo.hospital.persistence.model.FundingClearance;
import io.camunda.demo.hospital.persistence.model.Patient;
import io.camunda.demo.hospital.persistence.model.PathwayRecord;
import io.camunda.demo.hospital.persistence.model.Payment;
import io.camunda.demo.hospital.persistence.model.Referral;
import io.camunda.demo.hospital.persistence.model.RefundCase;
import io.camunda.demo.hospital.persistence.model.Report;
import io.camunda.demo.hospital.persistence.model.SlotRequest;
import io.camunda.demo.hospital.persistence.model.TreatmentCycle;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Proves what the worker project promises about its system of record, against an in-memory H2.
 *
 * <p>No cluster and no network are involved: the context starts the same beans the application
 * starts (so adding the datasource must not have broken any of them) and the datasource comes from
 * src/test/resources/application.properties, which points at an in-memory database so a test run
 * never writes into the developer's file database under java/data/.
 *
 * <p>Three promises are checked: the schema creates every table the persistence layer talks to,
 * every repository round-trips one row (which also exercises the foreign keys and the column
 * mapping), and the shipped seed script can be replayed on an existing database without failing and
 * without duplicating rows - the same thing the application does at every start-up.
 */
@SpringBootTest
@DisplayName("The H2 system of record of the pathway")
class HospitalDatabaseTest {

    /** Referral the test's own aggregate graph hangs off. */
    private static final String REFERRAL_ID = "REF-DB-TEST";
    private static final String PATIENT_ID = "PAT-DB-TEST";
    private static final String APPOINTMENT_ID = "APPT-DB-TEST";
    private static final String SLOT_REQUEST_ID = "SLOT-DB-TEST";
    private static final String CONSULTATION_ID = "CONS-DB-TEST";
    private static final String CYCLE_ID = "CYC-DB-TEST";
    private static final String AUTHORISATION_ID = "AUTH-DB-TEST";
    private static final String CLEARANCE_ID = "CLR-DB-TEST";
    private static final String PAYMENT_ID = "PAY-DB-TEST";
    private static final String REFUND_CASE_ID = "RFD-DB-TEST";
    private static final String LETTER_ID = "LTR-DB-TEST";
    private static final String ENQUIRY_ID = "ENQ-DB-TEST";
    private static final String AUDIT_ID = "AUD-DB-TEST";
    private static final String RECORD_ID = "PR-DB-TEST";
    private static final String REPORT_ID = "RPT-DB-TEST";
    private static final String EVENT_ID = "EVT-DB-TEST";

    /** Catalogue entry of the seeded reference data; the test's graph points at the seeded service. */
    private static final String SERVICE_CODE = "SVC-CARD-ASSESS";

    /** Whole seconds on purpose: H2 stores timestamps with microsecond precision. */
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 2, 1, 9, 15);
    private static final long PROCESS_INSTANCE_KEY = 2251799813685249L;

    /** The tables the persistence layer talks to, one per aggregate plus the catalogue. */
    private static final List<String> TABLES = List.of(
            "patient", "referral", "treatment_service", "appointment", "slot_request", "consultation",
            "treatment_cycle", "funding_authorisation", "funding_clearance", "payment", "refund_case",
            "clinic_letter", "enquiry", "access_audit", "pathway_record", "report", "event_log");

    @Autowired
    private HospitalDatabase database;

    @Autowired
    private DataSource dataSource;

    /** Meta queries of the test itself; no repository exposes INFORMATION_SCHEMA. */
    private JdbcTemplate meta;

    @BeforeEach
    void prepareMetaQueries() {
        meta = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("the schema creates every table of the pathway")
    void schemaCreatesEveryTable() {
        List<String> created = meta.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", String.class)
                .stream()
                .map(String::toLowerCase)
                .toList();

        assertThat(created).containsAll(TABLES);
    }

    @Test
    @DisplayName("every repository writes and reads one row")
    void everyRepositoryRoundTripsOneRow() {
        Patient patient = new Patient(PATIENT_ID, "999 000 1111", "Doe", "Sam", LocalDate.of(1968, 3, 9),
                "male", "sam.doe@example.org", "+44 7700 900999", true, WHEN);
        database.patients().insert(patient);

        Referral referral = new Referral(REFERRAL_ID, PATIENT_ID, "Hilltop Surgery", "Dr Owen Lee", "routine",
                "Follow-up of treated arrhythmia", "INC-DB-TEST", WHEN, "received");
        database.referrals().insert(referral);

        Appointment appointment = new Appointment(APPOINTMENT_ID, REFERRAL_ID, PATIENT_ID, SERVICE_CODE, WHEN,
                "Cardiology clinic", "CLIN-DB-TEST", "offered", "alternative", WHEN.plusDays(3));
        database.appointments().insert(appointment);

        SlotRequest slotRequest = new SlotRequest(SLOT_REQUEST_ID, REFERRAL_ID, SERVICE_CODE,
                LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10), "available", 2, "offered", WHEN,
                APPOINTMENT_ID);
        database.slotRequests().insert(slotRequest);

        Consultation consultation = new Consultation(CONSULTATION_ID, REFERRAL_ID, APPOINTMENT_ID, PATIENT_ID,
                "CLIN-DB-TEST", "face-to-face", WHEN, "Repeat the treatment cycle", "continue", "consent",
                "CLIN-DB-TEST", WHEN.plusHours(1));
        database.consultations().insert(consultation);

        TreatmentCycle cycle = new TreatmentCycle(CYCLE_ID, REFERRAL_ID, PATIENT_ID, SERVICE_CODE, 1,
                LocalDate.of(2026, 2, 12), LocalDate.of(2026, 2, 26), LocalDate.of(2026, 2, 12), "active",
                Boolean.TRUE, "continue");
        database.treatmentCycles().insert(cycle);

        FundingAuthorisation authorisation = new FundingAuthorisation(AUTHORISATION_ID, REFERRAL_ID, PATIENT_ID,
                CYCLE_ID, "FUNDER-DEMO", new BigDecimal("1250.00"), new BigDecimal("1200.00"), "GBP",
                "authorised", "Within tariff", WHEN, WHEN.plusDays(1));
        database.fundingAuthorisations().insert(authorisation);

        FundingClearance clearance = new FundingClearance(CLEARANCE_ID, AUTHORISATION_ID, REFERRAL_ID,
                "CLR-REF-1", new BigDecimal("1200.00"), "GBP", WHEN.plusDays(1),
                LocalDate.of(2026, 12, 31), "cleared");
        database.fundingClearances().insert(clearance);

        Payment payment = new Payment(PAYMENT_ID, REFERRAL_ID, PATIENT_ID, "CHG-DB-TEST",
                new BigDecimal("300.00"), "GBP", "paid", "PSP-REF-1", false, WHEN, WHEN.plusDays(2));
        database.payments().insert(payment);

        RefundCase refundCase = new RefundCase(REFUND_CASE_ID, PAYMENT_ID, REFERRAL_ID, "fund-transfer",
                new BigDecimal("300.00"), "GBP", "open", "requested", "CHG-OTHER", WHEN.plusDays(3), null);
        database.refundCases().insert(refundCase);

        ClinicLetter letter = new ClinicLetter(LETTER_ID, REFERRAL_ID, PATIENT_ID, CONSULTATION_ID, "outcome",
                "Hilltop Surgery", "Outcome of the cardiology consultation", "Consent recorded, cycle repeated",
                "draft", WHEN, null);
        database.clinicLetters().insert(letter);

        Enquiry enquiry = new Enquiry(ENQUIRY_ID, PATIENT_ID, REFERRAL_ID, "email", "Sam Doe",
                "sam.doe@example.org", "When is my next cycle?", "On 12 February.", "answered", WHEN,
                WHEN.plusHours(2));
        database.enquiries().insert(enquiry);

        AccessAudit audit = new AccessAudit(AUDIT_ID, "STAFF-77", PATIENT_ID, REFERRAL_ID, "grant", "approved",
                "Treating clinician", "PRIVACY-OFFICER", WHEN);
        database.accessAudits().append(audit);

        PathwayRecord record = new PathwayRecord(RECORD_ID, REFERRAL_ID, PATIENT_ID, PROCESS_INSTANCE_KEY,
                "treatment", true, LocalDate.of(2026, 2, 20), WHEN, "active", "Monitor the second cycle");
        database.pathwayRecords().insert(record);

        Report report = new Report(REPORT_ID, "pathway-volume", "2026-02", "reporting-worker", WHEN, 1,
                "generated", "referral,status\nREF-DB-TEST,received");
        database.reports().insert(report);

        EventLog event = new EventLog(EVENT_ID, PROCESS_INSTANCE_KEY, "Task_RecordOutcome", "record-refund-outcome",
                null, "job-completed", REFERRAL_ID, PATIENT_ID, WHEN, "{\"refundStatus\":\"completed\"}");
        database.eventLog().append(event);

        assertThat(database.patients().findById(PATIENT_ID)).contains(patient);
        assertThat(database.patients().findByNhsNumber("999 000 1111")).contains(patient);
        assertThat(database.patients().findAll()).contains(patient);
        assertThat(database.patients().existsById(PATIENT_ID)).isTrue();
        assertThat(database.referrals().findById(REFERRAL_ID)).contains(referral);
        assertThat(database.referrals().findByPatientId(PATIENT_ID)).containsExactly(referral);
        assertThat(database.treatmentServices().findById(SERVICE_CODE)).isPresent();
        assertThat(database.treatmentServices().findByCategory("Outpatient"))
                .extracting(service -> service.serviceCode())
                .containsExactly(SERVICE_CODE);
        assertThat(database.appointments().findById(APPOINTMENT_ID)).contains(appointment);
        assertThat(database.appointments().findByReferralId(REFERRAL_ID)).containsExactly(appointment);
        assertThat(database.appointments().findBookedBefore(WHEN.plusDays(1)))
                .as("the offer is not booked yet")
                .isEmpty();
        assertThat(database.slotRequests().findById(SLOT_REQUEST_ID)).contains(slotRequest);
        assertThat(database.slotRequests().findByReferralId(REFERRAL_ID)).containsExactly(slotRequest);
        assertThat(database.slotRequests().findOpenByReferralId(REFERRAL_ID)).isEmpty();
        assertThat(database.consultations().findById(CONSULTATION_ID)).contains(consultation);
        assertThat(database.consultations().findByReferralId(REFERRAL_ID)).containsExactly(consultation);
        assertThat(database.treatmentCycles().findById(CYCLE_ID)).contains(cycle);
        assertThat(database.treatmentCycles().findByReferralId(REFERRAL_ID)).containsExactly(cycle);
        assertThat(database.treatmentCycles().findByReferralServiceAndNumber(REFERRAL_ID, SERVICE_CODE, 1))
                .contains(cycle);
        assertThat(database.treatmentCycles().highestCycleNumber(REFERRAL_ID, SERVICE_CODE)).isEqualTo(1);
        assertThat(database.fundingAuthorisations().findById(AUTHORISATION_ID)).contains(authorisation);
        assertThat(database.fundingAuthorisations().findByReferralId(REFERRAL_ID)).containsExactly(authorisation);
        assertThat(database.fundingAuthorisations().authorisedTotal(REFERRAL_ID)).isEqualByComparingTo("1200.00");
        assertThat(database.fundingClearances().findById(CLEARANCE_ID)).contains(clearance);
        assertThat(database.fundingClearances().findByAuthorisationId(AUTHORISATION_ID)).containsExactly(clearance);
        assertThat(database.fundingClearances().findDrawableByReferralId(REFERRAL_ID)).contains(clearance);
        assertThat(database.payments().findById(PAYMENT_ID)).contains(payment);
        assertThat(database.payments().findByReferralId(REFERRAL_ID)).containsExactly(payment);
        assertThat(database.payments().findPaidByReferralId(REFERRAL_ID)).contains(payment);
        assertThat(database.refundCases().findById(REFUND_CASE_ID)).contains(refundCase);
        assertThat(database.refundCases().findByReferralId(REFERRAL_ID)).containsExactly(refundCase);
        assertThat(database.refundCases().findByPaymentId(PAYMENT_ID)).containsExactly(refundCase);
        assertThat(database.refundCases().findOpenByReferralId(REFERRAL_ID, "fund-transfer")).contains(refundCase);
        assertThat(database.clinicLetters().findById(LETTER_ID)).contains(letter);
        assertThat(database.clinicLetters().findByReferralId(REFERRAL_ID)).containsExactly(letter);
        assertThat(database.clinicLetters().findDrafts()).contains(letter);
        assertThat(database.enquiries().findById(ENQUIRY_ID)).contains(enquiry);
        assertThat(database.enquiries().findByReferralId(REFERRAL_ID)).containsExactly(enquiry);
        assertThat(database.enquiries().findByStatus("answered")).containsExactly(enquiry);
        assertThat(database.accessAudits().findById(AUDIT_ID)).contains(audit);
        assertThat(database.accessAudits().findByStaffUserId("STAFF-77")).containsExactly(audit);
        assertThat(database.accessAudits().findByTargetReferralId(REFERRAL_ID)).containsExactly(audit);
        assertThat(database.pathwayRecords().findById(RECORD_ID)).contains(record);
        assertThat(database.pathwayRecords().findByReferralId(REFERRAL_ID)).containsExactly(record);
        assertThat(database.pathwayRecords().findByProcessInstanceKey(PROCESS_INSTANCE_KEY)).contains(record);
        assertThat(database.pathwayRecords().findDueForReview(LocalDate.of(2026, 3, 1))).contains(record);
        assertThat(database.reports().findById(REPORT_ID)).contains(report);
        assertThat(database.reports().findByReportType("pathway-volume")).containsExactly(report);
        assertThat(database.reports().findLatestByReportType("pathway-volume")).contains(report);
        assertThat(database.eventLog().findById(EVENT_ID)).contains(event);
        assertThat(database.eventLog().findByProcessInstanceKey(PROCESS_INSTANCE_KEY)).contains(event);
        assertThat(database.eventLog().findByJobType("record-refund-outcome")).containsExactly(event);
        assertThat(database.eventLog().findByReferralId(REFERRAL_ID)).containsExactly(event);

        Referral closed = new Referral(referral.referralId(), referral.patientId(),
                referral.referringOrganisation(), referral.referringClinician(), referral.priority(),
                referral.suspectedCondition(), referral.externalReference(), referral.receivedOn(), "closed");
        database.referrals().update(closed);

        assertThat(database.referrals().findById(REFERRAL_ID)).contains(closed);
        assertThat(database.referrals().findByStatus("closed")).containsExactly(closed);
    }

    @Test
    @DisplayName("the seed re-applies the catalogue and inserts the demo rows only once")
    void seedIsReplayedWithoutDuplicatingRows() throws SQLException {
        // A database of its own: the test decides what the replayed script finds, so it can show
        // both halves of the contract - a worker-written value survives, an absent key is filled.
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:seed-replay", "sa", "")) {
            replaySeed(connection);
            replaySeed(connection);

            assertThat(countRows(connection, "patient", "patient_id", "PAT-1001")).isEqualTo(1);
            assertThat(countRows(connection, "referral", "referral_id", "REF-1001")).isEqualTo(1);
            assertThat(countRows(connection, "treatment_service", "service_code", "SVC-LAB-BLOODS")).isEqualTo(1);
            assertThat(seeded(connection, "SELECT date_of_birth FROM patient WHERE patient_id = 'PAT-1001'"))
                    .as("the immutable part of the demo patient is the seeded one")
                    .isEqualTo("1974-05-18");
            assertThat(seeded(connection, "SELECT external_reference FROM referral WHERE referral_id = 'REF-1001'"))
                    .as("the immutable part of the demo referral is the seeded one")
                    .isEqualTo("INC-1001");

            // The intake and review workers write exactly these two columns of the demo subject.
            connection.createStatement().executeUpdate(
                    "UPDATE patient SET contact_phone = '07700 900123' WHERE patient_id = 'PAT-1001'");
            connection.createStatement().executeUpdate(
                    "UPDATE referral SET status = 'accepted' WHERE referral_id = 'REF-1001'");

            replaySeed(connection);

            assertThat(seeded(connection, "SELECT contact_phone FROM patient WHERE patient_id = 'PAT-1001'"))
                    .as("a restart keeps the contact details a worker wrote")
                    .isEqualTo("07700 900123");
            assertThat(seeded(connection, "SELECT status FROM referral WHERE referral_id = 'REF-1001'"))
                    .as("a restart keeps the status a worker wrote")
                    .isEqualTo("accepted");

            connection.createStatement().executeUpdate("DELETE FROM referral WHERE referral_id = 'REF-1001'");
            replaySeed(connection);

            assertThat(seeded(connection, "SELECT status FROM referral WHERE referral_id = 'REF-1001'"))
                    .as("a key the database does not have is inserted by the replay")
                    .isEqualTo("received");
            assertThat(countRows(connection, "referral", "referral_id", "REF-1001")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the foreign keys reject a row whose parent does not exist")
    void foreignKeysRejectUnknownParents() {
        Referral orphan = new Referral("REF-DB-ORPHAN", "PAT-DOES-NOT-EXIST", "Hilltop Surgery", "Dr Owen Lee",
                "routine", "No such patient", "INC-DB-ORPHAN", WHEN, "received");

        assertThatThrownBy(() -> database.referrals().insert(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the newest report of a type is found when several days are on record")
    void findLatestByReportTypeReturnsTheNewestOfSeveralReports() {
        String reportType = "RPT-DB-LATEST";
        Report older = new Report(reportType + "-2026-02-01", reportType, "2026-02", "reporting-worker",
                WHEN, 3, "generated", "older day");
        Report newer = new Report(reportType + "-2026-03-01", reportType, "2026-03", "reporting-worker",
                WHEN.plusDays(28), 5, "generated", "newer day");
        database.reports().insert(older);
        database.reports().insert(newer);

        // The statement this finder used before the repair selected every report of the type and
        // asked the client for one row, which is what a monitoring run of a second day runs into.
        assertThatThrownBy(() -> meta.queryForObject(
                "SELECT report_id FROM report WHERE report_type = ? ORDER BY generated_on DESC",
                String.class, reportType))
                .as("what the unlimited 'latest report' statement does with two reports of one type")
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);

        assertThat(database.reports().findByReportType(reportType))
                .as("both days stay readable, newest first")
                .extracting(Report::reportId)
                .containsExactly(newer.reportId(), older.reportId());
        assertThat(database.reports().findLatestByReportType(reportType))
                .as("the newest report of the type, not an exception")
                .contains(newer);
    }

    @Test
    @DisplayName("the audit trail of an event whose subject is not on record is written")
    void eventLogRecordsASubjectTheHospitalDoesNotHave() {
        EventLog early = new EventLog("EVT-DB-EARLY", null, null, null, "Hospital pathway: Incoming referral package",
                "message-received", "REF-DB-UNKNOWN", "PAT-DB-UNKNOWN", WHEN, "{}");

        database.eventLog().append(early);

        assertThat(database.referrals().findById("REF-DB-UNKNOWN"))
                .as("the event arrived before the referral was registered")
                .isEmpty();
        assertThat(database.eventLog().findByReferralId("REF-DB-UNKNOWN"))
                .as("the trail exists to record exactly such an event")
                .containsExactly(early);
        assertThat(meta.queryForList("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'EVENT_LOG'", String.class))
                .as("the log's subject columns are references, not foreign keys")
                .doesNotContain("FK_EVENT_LOG_REFERRAL", "FK_EVENT_LOG_PATIENT");
    }

    @Test
    @DisplayName("an access decision about a subject that is not on record is written")
    void accessAuditRecordsASubjectTheHospitalDoesNotHave() {
        // An access request is an entry message of the pathway, so the referral it names can be one
        // the hospital has not registered yet. The audit row has to be written anyway - a foreign
        // key here failed the record-access-decision job and left the pathway instance active.
        AccessAudit decision = new AccessAudit("AUD-DB-UNKNOWN", "STAFF-1", "PAT-DB-UNKNOWN",
                "REF-DB-UNKNOWN", "role change", "approved", "verified by the line manager",
                "service manager", WHEN);

        assertThat(database.referrals().findById("REF-DB-UNKNOWN")).isEmpty();
        database.accessAudits().append(decision);

        assertThat(database.accessAudits().findByTargetReferralId("REF-DB-UNKNOWN"))
                .as("the audit records the decision and the subject it names")
                .containsExactly(decision);
        assertThat(meta.queryForList("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'ACCESS_AUDIT'", String.class))
                .as("the audit's subject columns are references, not foreign keys")
                .doesNotContain("FK_ACCESS_AUDIT_REFERRAL", "FK_ACCESS_AUDIT_PATIENT");
    }

    /** Number of rows of one table with this key, read without a repository. */
    private int countRows(String table, String keyColumn, String key) {
        return meta.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + keyColumn + " = ?",
                Integer.class, key);
    }

    /** Number of rows of one table with this key in a database of the test's own. */
    private int countRows(Connection connection, String table, String keyColumn, String key) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + keyColumn + " = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** One value of the seeded row, as text, read from a database of the test's own. */
    private String seeded(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** Replays the two shipped scripts, the way the application does at every start-up. */
    private void replaySeed(Connection connection) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/data.sql"));
    }
}

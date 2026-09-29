-- ===========================================================================
-- Hospital patient pathway - reference data of the embedded H2 system of
-- record.
--
-- The application replays this script at every start-up
-- (spring.sql.init.mode=always), so the script has to be idempotent and it has
-- to distinguish two kinds of rows:
--
--   * the service catalogue (treatment_service) is immutable reference data:
--     every start re-applies it with MERGE ... KEY, so a corrected catalogue
--     entry reaches an existing database. Rows a worker registered for a
--     service the catalogue did not know are left alone.
--
--   * the demo patient and its referral (PAT-1001, REF-1001) are mutable: the
--     intake, review and later steps write their contact details, their status
--     and their priority. They are therefore INSERTed once and only when their
--     key is absent, so a restart keeps what a run wrote instead of silently
--     reverting it. That also means a demo run is resumed, not reset, by a
--     restart: delete java/data/ (the H2 file of the runtime database) to start
--     the demo from the seeded state again.
--
-- The demo patient and its referral use the identifiers that
-- hospital.simulation.default-patient-id / default-referral-id in
-- application.properties name, so the simulated external parties publish their
-- messages for a subject the database already knows. Keep the two in sync when
-- those switches are changed.
-- ===========================================================================

-- Service catalogue the referral, slot and treatment workers book against.
MERGE INTO treatment_service (service_code, service_name, category, default_cycles, lead_time_days, active)
    KEY (service_code)
    VALUES ('SVC-CARD-ASSESS', 'Cardiology assessment', 'Outpatient', 1, 14, TRUE);

MERGE INTO treatment_service (service_code, service_name, category, default_cycles, lead_time_days, active)
    KEY (service_code)
    VALUES ('SVC-DIAG-MRI', 'Magnetic resonance imaging', 'Diagnostics', 1, 7, TRUE);

MERGE INTO treatment_service (service_code, service_name, category, default_cycles, lead_time_days, active)
    KEY (service_code)
    VALUES ('SVC-LAB-BLOODS', 'Blood panel', 'Laboratory', 3, 2, TRUE);

MERGE INTO treatment_service (service_code, service_name, category, default_cycles, lead_time_days, active)
    KEY (service_code)
    VALUES ('SVC-PHYSIO', 'Physiotherapy course', 'Therapy', 6, 5, TRUE);

MERGE INTO treatment_service (service_code, service_name, category, default_cycles, lead_time_days, active)
    KEY (service_code)
    VALUES ('SVC-SURG-MINOR', 'Minor surgical procedure', 'Surgery', 1, 21, TRUE);

-- Demo patient and referral of the default demo path, inserted once.
INSERT INTO patient (patient_id, nhs_number, family_name, given_name, date_of_birth, sex,
                     contact_email, contact_phone, interpreter_required, registered_on)
    SELECT 'PAT-1001', '999 111 2222', 'Morgan', 'Alex', DATE '1974-05-18', 'female',
           'alex.morgan@example.org', '+44 7700 900123', FALSE, TIMESTAMP '2026-01-04 08:30:00'
    WHERE NOT EXISTS (SELECT 1 FROM patient WHERE patient_id = 'PAT-1001');

INSERT INTO referral (referral_id, patient_id, referring_organisation, referring_clinician, priority,
                      suspected_condition, external_reference, received_on, status)
    SELECT 'REF-1001', 'PAT-1001', 'Riverside General Practice', 'Dr Priya Raman', 'urgent',
           'Suspected cardiac arrhythmia', 'INC-1001', TIMESTAMP '2026-01-04 09:00:00', 'received'
    WHERE NOT EXISTS (SELECT 1 FROM referral WHERE referral_id = 'REF-1001');

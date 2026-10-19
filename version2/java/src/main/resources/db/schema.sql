-- ===========================================================================
-- Hospital patient pathway - schema of the embedded H2 system of record.
--
-- The application replays this script at every start-up, because the database
-- is a file that survives the restart (see spring.sql.init.* and the datasource
-- URL in application.properties). Every statement is therefore idempotent:
-- CREATE TABLE IF NOT EXISTS creates what is missing and leaves an existing
-- table untouched, so a second start against an existing file database succeeds
-- instead of failing with "table already exists".
--
-- The tables are the business objects of the pathway and are declared in
-- dependency order (a table follows the tables its foreign keys point at).
-- Identifier columns are the pathway's own references (PAT-1001, REF-1001,
-- INC-1001, ...) as the workers read them from the model variables.
-- ===========================================================================

-- The person the pathway is run for.
CREATE TABLE IF NOT EXISTS patient (
    patient_id           VARCHAR(64)   NOT NULL,
    nhs_number           VARCHAR(32),
    family_name          VARCHAR(128)  NOT NULL,
    given_name           VARCHAR(128)  NOT NULL,
    date_of_birth        DATE          NOT NULL,
    sex                  VARCHAR(16),
    contact_email        VARCHAR(255),
    contact_phone        VARCHAR(64),
    interpreter_required BOOLEAN       NOT NULL DEFAULT FALSE,
    registered_on        TIMESTAMP     NOT NULL,
    CONSTRAINT pk_patient PRIMARY KEY (patient_id),
    CONSTRAINT uq_patient_nhs_number UNIQUE (nhs_number)
);

-- The referral the whole pathway hangs off: every other object points at it.
CREATE TABLE IF NOT EXISTS referral (
    referral_id           VARCHAR(64)  NOT NULL,
    patient_id            VARCHAR(64)  NOT NULL,
    referring_organisation VARCHAR(255),
    referring_clinician   VARCHAR(128),
    priority              VARCHAR(32)  NOT NULL,
    suspected_condition   VARCHAR(255),
    external_reference    VARCHAR(64),
    received_on           TIMESTAMP    NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    CONSTRAINT pk_referral PRIMARY KEY (referral_id),
    CONSTRAINT fk_referral_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id)
);

-- Catalogue of the services the hospital can deliver; seeded reference data.
CREATE TABLE IF NOT EXISTS treatment_service (
    service_code   VARCHAR(32)  NOT NULL,
    service_name   VARCHAR(128) NOT NULL,
    category       VARCHAR(64)  NOT NULL,
    default_cycles INTEGER      NOT NULL,
    lead_time_days INTEGER      NOT NULL,
    active         BOOLEAN      NOT NULL,
    CONSTRAINT pk_treatment_service PRIMARY KEY (service_code)
);

-- A booked consultation slot offered to the patient.
CREATE TABLE IF NOT EXISTS appointment (
    appointment_id         VARCHAR(64) NOT NULL,
    referral_id            VARCHAR(64) NOT NULL,
    patient_id             VARCHAR(64) NOT NULL,
    service_code           VARCHAR(32) NOT NULL,
    scheduled_for          TIMESTAMP   NOT NULL,
    location               VARCHAR(128),
    clinician_id           VARCHAR(64),
    status                 VARCHAR(32) NOT NULL,
    patient_decision       VARCHAR(32),
    alternative_offered_for TIMESTAMP,
    CONSTRAINT pk_appointment PRIMARY KEY (appointment_id),
    CONSTRAINT fk_appointment_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_appointment_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id),
    CONSTRAINT fk_appointment_service FOREIGN KEY (service_code) REFERENCES treatment_service (service_code)
);

-- The waitlist / slot search: what was asked for, how often it was tried and
-- whether it ended in a booked appointment.
CREATE TABLE IF NOT EXISTS slot_request (
    slot_request_id         VARCHAR(64) NOT NULL,
    referral_id             VARCHAR(64) NOT NULL,
    service_code            VARCHAR(32) NOT NULL,
    earliest_date           DATE        NOT NULL,
    latest_date             DATE        NOT NULL,
    availability            VARCHAR(32) NOT NULL,
    attempt_count           INTEGER     NOT NULL,
    status                  VARCHAR(32) NOT NULL,
    requested_on            TIMESTAMP   NOT NULL,
    resolved_appointment_id VARCHAR(64),
    CONSTRAINT pk_slot_request PRIMARY KEY (slot_request_id),
    CONSTRAINT fk_slot_request_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_slot_request_service FOREIGN KEY (service_code) REFERENCES treatment_service (service_code),
    CONSTRAINT fk_slot_request_appointment FOREIGN KEY (resolved_appointment_id) REFERENCES appointment (appointment_id)
);

-- The consultation itself, including the patient's consent decision.
CREATE TABLE IF NOT EXISTS consultation (
    consultation_id      VARCHAR(64)  NOT NULL,
    referral_id          VARCHAR(64)  NOT NULL,
    appointment_id       VARCHAR(64),
    patient_id           VARCHAR(64)  NOT NULL,
    clinician_id         VARCHAR(64)  NOT NULL,
    consultation_type    VARCHAR(64),
    occurred_on          TIMESTAMP    NOT NULL,
    recommendation       VARCHAR(255),
    outcome              VARCHAR(64),
    consent_status       VARCHAR(32),
    consent_recorded_by  VARCHAR(64),
    consent_recorded_on  TIMESTAMP,
    CONSTRAINT pk_consultation PRIMARY KEY (consultation_id),
    CONSTRAINT fk_consultation_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_consultation_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (appointment_id),
    CONSTRAINT fk_consultation_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id)
);

-- One pass through the treatment a service delivers; the pathway repeats it
-- while the test results say the patient is fit to continue.
CREATE TABLE IF NOT EXISTS treatment_cycle (
    treatment_cycle_id            VARCHAR(64) NOT NULL,
    referral_id                   VARCHAR(64) NOT NULL,
    patient_id                    VARCHAR(64) NOT NULL,
    service_code                  VARCHAR(32) NOT NULL,
    cycle_number                  INTEGER     NOT NULL,
    planned_start                 DATE,
    planned_end                   DATE,
    started_on                    DATE,
    status                        VARCHAR(32) NOT NULL,
    test_results_fit_to_continue  BOOLEAN,
    review_outcome                VARCHAR(64),
    CONSTRAINT pk_treatment_cycle PRIMARY KEY (treatment_cycle_id),
    CONSTRAINT uq_treatment_cycle_number UNIQUE (referral_id, service_code, cycle_number),
    CONSTRAINT fk_treatment_cycle_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_treatment_cycle_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id),
    CONSTRAINT fk_treatment_cycle_service FOREIGN KEY (service_code) REFERENCES treatment_service (service_code)
);

-- What the funder was asked for and what it decided.
CREATE TABLE IF NOT EXISTS funding_authorisation (
    authorisation_id    VARCHAR(64)   NOT NULL,
    referral_id         VARCHAR(64)   NOT NULL,
    patient_id          VARCHAR(64)   NOT NULL,
    treatment_cycle_id  VARCHAR(64),
    funder_code         VARCHAR(64)   NOT NULL,
    requested_amount    DECIMAL(12,2) NOT NULL,
    authorised_amount   DECIMAL(12,2),
    currency            VARCHAR(3)    NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    decision_reason     VARCHAR(255),
    requested_on        TIMESTAMP     NOT NULL,
    decided_on          TIMESTAMP,
    CONSTRAINT pk_funding_authorisation PRIMARY KEY (authorisation_id),
    CONSTRAINT fk_funding_authorisation_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_funding_authorisation_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id),
    CONSTRAINT fk_funding_authorisation_cycle FOREIGN KEY (treatment_cycle_id) REFERENCES treatment_cycle (treatment_cycle_id)
);

-- The money released against an authorisation, with its validity window.
CREATE TABLE IF NOT EXISTS funding_clearance (
    clearance_id        VARCHAR(64)   NOT NULL,
    authorisation_id    VARCHAR(64)   NOT NULL,
    referral_id         VARCHAR(64)   NOT NULL,
    clearance_reference VARCHAR(64)   NOT NULL,
    cleared_amount      DECIMAL(12,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    cleared_on          TIMESTAMP     NOT NULL,
    valid_until         DATE,
    status              VARCHAR(32)   NOT NULL,
    CONSTRAINT pk_funding_clearance PRIMARY KEY (clearance_id),
    CONSTRAINT fk_funding_clearance_authorisation FOREIGN KEY (authorisation_id)
        REFERENCES funding_authorisation (authorisation_id),
    CONSTRAINT fk_funding_clearance_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id)
);

-- The charge raised for the treatment and what the payment provider answered.
CREATE TABLE IF NOT EXISTS payment (
    payment_id           VARCHAR(64)   NOT NULL,
    referral_id          VARCHAR(64)   NOT NULL,
    patient_id           VARCHAR(64)   NOT NULL,
    charge_reference     VARCHAR(64)   NOT NULL,
    amount               DECIMAL(12,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    status               VARCHAR(32)   NOT NULL,
    provider_reference   VARCHAR(64),
    prior_charge_found   BOOLEAN       NOT NULL DEFAULT FALSE,
    requested_on         TIMESTAMP     NOT NULL,
    settled_on           TIMESTAMP,
    CONSTRAINT pk_payment PRIMARY KEY (payment_id),
    CONSTRAINT fk_payment_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_payment_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id)
);

-- The money that has to go back (refund) or to the other booking (fund transfer).
CREATE TABLE IF NOT EXISTS refund_case (
    refund_case_id          VARCHAR(64)   NOT NULL,
    payment_id              VARCHAR(64)   NOT NULL,
    referral_id             VARCHAR(64)   NOT NULL,
    case_type               VARCHAR(32)   NOT NULL,
    amount                  DECIMAL(12,2) NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    status                  VARCHAR(32)   NOT NULL,
    outcome                 VARCHAR(64),
    linked_booking_reference VARCHAR(64),
    opened_on               TIMESTAMP     NOT NULL,
    closed_on               TIMESTAMP,
    CONSTRAINT pk_refund_case PRIMARY KEY (refund_case_id),
    CONSTRAINT fk_refund_case_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_refund_case_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id)
);

-- Letter to the patient, the general practitioner or the referring organisation.
CREATE TABLE IF NOT EXISTS clinic_letter (
    letter_id       VARCHAR(64)   NOT NULL,
    referral_id     VARCHAR(64)   NOT NULL,
    patient_id      VARCHAR(64)   NOT NULL,
    consultation_id VARCHAR(64),
    letter_type     VARCHAR(64)   NOT NULL,
    recipient       VARCHAR(255)  NOT NULL,
    subject         VARCHAR(255)  NOT NULL,
    body_summary    VARCHAR(2000),
    status          VARCHAR(32)   NOT NULL,
    created_on      TIMESTAMP     NOT NULL,
    sent_on         TIMESTAMP,
    CONSTRAINT pk_clinic_letter PRIMARY KEY (letter_id),
    CONSTRAINT fk_clinic_letter_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_clinic_letter_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id),
    CONSTRAINT fk_clinic_letter_consultation FOREIGN KEY (consultation_id) REFERENCES consultation (consultation_id)
);

-- A question from the patient (or an external party) and its answer.
CREATE TABLE IF NOT EXISTS enquiry (
    enquiry_id      VARCHAR(64)   NOT NULL,
    patient_id      VARCHAR(64),
    referral_id     VARCHAR(64),
    channel         VARCHAR(32)   NOT NULL,
    raised_by       VARCHAR(128),
    contact_details VARCHAR(255),
    question        VARCHAR(1000) NOT NULL,
    response        VARCHAR(1000),
    status          VARCHAR(32)   NOT NULL,
    received_on     TIMESTAMP     NOT NULL,
    answered_on     TIMESTAMP,
    CONSTRAINT pk_enquiry PRIMARY KEY (enquiry_id),
    CONSTRAINT fk_enquiry_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id),
    CONSTRAINT fk_enquiry_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id)
);

-- Every access decision of the identity and access segment, kept for the audit.
--
-- The subject columns are references, not foreign keys. An access request is an entry message of
-- the pathway (Inbox_P16_Start_Access), so it can name a referral or a patient the hospital has not
-- registered yet - the same reason event_log carries no foreign key. This table records a decision;
-- a constraint that rejected the row would fail the step that has to report it instead.
CREATE TABLE IF NOT EXISTS access_audit (
    audit_id           VARCHAR(64)  NOT NULL,
    staff_user_id      VARCHAR(64)  NOT NULL,
    target_patient_id  VARCHAR(64),
    target_referral_id VARCHAR(64),
    action             VARCHAR(32)  NOT NULL,
    decision           VARCHAR(32)  NOT NULL,
    reason             VARCHAR(255),
    decided_by         VARCHAR(64),
    decided_on         TIMESTAMP    NOT NULL,
    CONSTRAINT pk_access_audit PRIMARY KEY (audit_id)
);

-- The monitoring / follow-up record of a running pathway, bound to its instance.
CREATE TABLE IF NOT EXISTS pathway_record (
    pathway_record_id   VARCHAR(64)  NOT NULL,
    referral_id         VARCHAR(64)  NOT NULL,
    patient_id          VARCHAR(64)  NOT NULL,
    process_instance_key BIGINT,
    current_stage       VARCHAR(64)  NOT NULL,
    monitoring_flag     BOOLEAN      NOT NULL DEFAULT FALSE,
    follow_up_due       DATE,
    last_reviewed_on    TIMESTAMP,
    status              VARCHAR(32)  NOT NULL,
    notes               VARCHAR(1000),
    CONSTRAINT pk_pathway_record PRIMARY KEY (pathway_record_id),
    CONSTRAINT fk_pathway_record_referral FOREIGN KEY (referral_id) REFERENCES referral (referral_id),
    CONSTRAINT fk_pathway_record_patient FOREIGN KEY (patient_id) REFERENCES patient (patient_id)
);

-- A report the pathway generated; the body is kept as text so the demo can
-- show what the worker produced.
CREATE TABLE IF NOT EXISTS report (
    report_id    VARCHAR(64)  NOT NULL,
    report_type  VARCHAR(64)  NOT NULL,
    report_scope VARCHAR(255) NOT NULL,
    generated_by VARCHAR(128) NOT NULL,
    generated_on TIMESTAMP    NOT NULL,
    row_count    INTEGER      NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    content      CLOB,
    CONSTRAINT pk_report PRIMARY KEY (report_id)
);

-- Audit trail of everything the workers did or received, incl. the corridor
-- messages that arrive from outside the process. The referral and the patient
-- are recorded as plain subject references, deliberately without a foreign key:
-- the log exists to record events that arrive before the hospital has the
-- subject on record (an entry message for a referral the pathway has not
-- registered yet, a message for an identifier a simulation switch points at),
-- and a constraint here would turn such an event into a failure of the step
-- that received it - the trail would be missing exactly the events it exists
-- for.
CREATE TABLE IF NOT EXISTS event_log (
    event_id             VARCHAR(64)  NOT NULL,
    process_instance_key BIGINT,
    element_id           VARCHAR(128),
    job_type             VARCHAR(128),
    message_name         VARCHAR(255),
    event_type           VARCHAR(64)  NOT NULL,
    referral_id          VARCHAR(64),
    patient_id           VARCHAR(64),
    occurred_on          TIMESTAMP    NOT NULL,
    payload              CLOB,
    CONSTRAINT pk_event_log PRIMARY KEY (event_id)
);

-- A database that was created by an earlier revision still carries the two
-- foreign keys of this table; the script is replayed at every start-up, so it
-- drops them where they exist. DROP CONSTRAINT IF EXISTS keeps the replay
-- idempotent, and a fresh database simply has nothing to drop here.
ALTER TABLE event_log DROP CONSTRAINT IF EXISTS fk_event_log_referral;
ALTER TABLE event_log DROP CONSTRAINT IF EXISTS fk_event_log_patient;

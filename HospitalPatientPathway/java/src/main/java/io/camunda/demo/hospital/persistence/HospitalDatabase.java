package io.camunda.demo.hospital.persistence;

import org.springframework.stereotype.Component;

/**
 * The single entry point to the pathway's system of record.
 *
 * <p>A caller - in practice a job worker - has this bean injected and reaches the aggregates
 * through its accessors. That is what keeps JDBC out of the rest of the application: no
 * {@code JdbcClient}, no {@code ResultSet} and no SQL string crosses this package boundary, because
 * a worker that needs a referral calls {@link #referrals()} and gets a
 * {@link io.camunda.demo.hospital.persistence.model.Referral} back.
 *
 * <p>The accessors are named after the aggregates, so the facade reads like the pathway: register a
 * patient, file a referral, offer an appointment, hold a consultation, deliver a treatment cycle,
 * ask the funder, clear the money, charge, reverse, write the letter, answer the enquiry, decide the
 * access, monitor the pathway, report on it, and log the event.
 */
@Component
public class HospitalDatabase {

    private final PatientRepository patients;
    private final ReferralRepository referrals;
    private final TreatmentServiceRepository treatmentServices;
    private final AppointmentRepository appointments;
    private final SlotRequestRepository slotRequests;
    private final ConsultationRepository consultations;
    private final TreatmentCycleRepository treatmentCycles;
    private final FundingAuthorisationRepository fundingAuthorisations;
    private final FundingClearanceRepository fundingClearances;
    private final PaymentRepository payments;
    private final RefundCaseRepository refundCases;
    private final ClinicLetterRepository clinicLetters;
    private final EnquiryRepository enquiries;
    private final AccessAuditRepository accessAudits;
    private final PathwayRecordRepository pathwayRecords;
    private final ReportRepository reports;
    private final EventLogRepository eventLog;

    public HospitalDatabase(
            PatientRepository patients,
            ReferralRepository referrals,
            TreatmentServiceRepository treatmentServices,
            AppointmentRepository appointments,
            SlotRequestRepository slotRequests,
            ConsultationRepository consultations,
            TreatmentCycleRepository treatmentCycles,
            FundingAuthorisationRepository fundingAuthorisations,
            FundingClearanceRepository fundingClearances,
            PaymentRepository payments,
            RefundCaseRepository refundCases,
            ClinicLetterRepository clinicLetters,
            EnquiryRepository enquiries,
            AccessAuditRepository accessAudits,
            PathwayRecordRepository pathwayRecords,
            ReportRepository reports,
            EventLogRepository eventLog) {
        this.patients = patients;
        this.referrals = referrals;
        this.treatmentServices = treatmentServices;
        this.appointments = appointments;
        this.slotRequests = slotRequests;
        this.consultations = consultations;
        this.treatmentCycles = treatmentCycles;
        this.fundingAuthorisations = fundingAuthorisations;
        this.fundingClearances = fundingClearances;
        this.payments = payments;
        this.refundCases = refundCases;
        this.clinicLetters = clinicLetters;
        this.enquiries = enquiries;
        this.accessAudits = accessAudits;
        this.pathwayRecords = pathwayRecords;
        this.reports = reports;
        this.eventLog = eventLog;
    }

    /** The patients the pathway is run for. */
    public PatientRepository patients() {
        return patients;
    }

    /** The referrals every other aggregate hangs off. */
    public ReferralRepository referrals() {
        return referrals;
    }

    /** The catalogue of services the hospital delivers. */
    public TreatmentServiceRepository treatmentServices() {
        return treatmentServices;
    }

    /** The appointment offers and their patient decisions. */
    public AppointmentRepository appointments() {
        return appointments;
    }

    /** The waitlist requests of the slot search. */
    public SlotRequestRepository slotRequests() {
        return slotRequests;
    }

    /** The consultations and the consent decisions taken in them. */
    public ConsultationRepository consultations() {
        return consultations;
    }

    /** The treatment cycles the clinical review repeats. */
    public TreatmentCycleRepository treatmentCycles() {
        return treatmentCycles;
    }

    /** The funding requests and the funder's decisions. */
    public FundingAuthorisationRepository fundingAuthorisations() {
        return fundingAuthorisations;
    }

    /** The money released against an authorisation. */
    public FundingClearanceRepository fundingClearances() {
        return fundingClearances;
    }

    /** The charges and the payment provider's answers. */
    public PaymentRepository payments() {
        return payments;
    }

    /** The refund and fund-transfer cases. */
    public RefundCaseRepository refundCases() {
        return refundCases;
    }

    /** The letters the pathway wrote. */
    public ClinicLetterRepository clinicLetters() {
        return clinicLetters;
    }

    /** The enquiries and their answers. */
    public EnquiryRepository enquiries() {
        return enquiries;
    }

    /** The access decisions of the identity and access segment. */
    public AccessAuditRepository accessAudits() {
        return accessAudits;
    }

    /** The monitoring and follow-up records of running pathways. */
    public PathwayRecordRepository pathwayRecords() {
        return pathwayRecords;
    }

    /** The reports the pathway generated. */
    public ReportRepository reports() {
        return reports;
    }

    /** The audit trail of everything that happened to the pathway. */
    public EventLogRepository eventLog() {
        return eventLog;
    }
}

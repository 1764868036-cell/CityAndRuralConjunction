#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hpas.v1.bpmn generator - Hospital Patient Administration System (fresh rebuild).

Architecture (approved Batch 1 framework):
  - Single-instance full-pathway orchestration, correlation key = referralId
  - Main chain (embedded subprocesses): SP01 -> ... -> SP08 -> park at closure catch
  - Event-driven domains as NON-INTERRUPTING event subprocesses (triggeredByEvent,
    isInterrupting="false" start) : SP03B, SP09..SP16 - can trigger any time, multiple times
  - 7 external black-box pools + hospital pool (1 coordination lane at top;
    role lanes inside embedded main-chain subprocesses)
  - Internal handoffs published by sendTask workers (Batch 3); external messages
    arrive via REST/demo publishing
  - Zeebe 8.9 verified syntax: <zeebe:subscription correlationKey> on <bpmn:message>,
    <zeebe:taskDefinition retries>, <zeebe:assignmentDefinition candidateGroups>

Output: ../hpas.v1.bpmn  (BPMN 2.0 XML + full DI)
"""
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "hpas.v1.bpmn")

# ----------------------------------------------------------------------------
# messages: id -> (name, external_pool or None)
# ----------------------------------------------------------------------------
MSG = {
    "Msg_Referral":              ("HPAS: Referral received", "Referrer"),
    "Msg_Supplementary":         ("HPAS: Supplementary information received", "Referrer"),
    "Msg_MissingInfoRequest":    ("HPAS: Missing information request", "Referrer"),
    "Msg_ReferralOutcome":       ("HPAS: Referral outcome notification", "Referrer"),
    "Msg_SlotSearch":            ("HPAS: Slot search request", "Scheduling"),
    "Msg_SchedulingResult":      ("HPAS: Scheduling result", "Scheduling"),
    "Msg_AppointmentLetter":    ("HPAS: Appointment letter dispatch", "Correspondence"),
    "Msg_ClinicVisitCompleted":  ("HPAS: Clinic visit completed", "Patient"),
    "Msg_AppointmentChange":     ("HPAS: Appointment change requested", "Patient"),
    "Msg_ExternalAvailability":  ("HPAS: External availability request", "ExtTreat"),
    "Msg_ExternalAvailabilityResult": ("HPAS: External availability result", "ExtTreat"),
    "Msg_ExternalSlotReleased":  ("HPAS: External slot released", "ExtTreat"),
    "Msg_LabRequest":            ("HPAS: Blood test request", "ExtTreat"),
    "Msg_LabResult":             ("HPAS: Lab result", "ExtTreat"),
    "Msg_FundingApprovalRequest":("HPAS: Funding approval request", "Funder"),
    "Msg_FundingApproval":       ("HPAS: Funding approval received", "Funder"),
    "Msg_PaymentRequest":        ("HPAS: Secure payment request", "PSP"),
    "Msg_PaymentStatus":         ("HPAS: Payment status", "PSP"),
    "Msg_PaymentIssueNotice":    ("HPAS: Payment issue notice", "Correspondence"),
    "Msg_RefundRequest":         ("HPAS: Refund request", "PSP"),
    "Msg_RefundResult":          ("HPAS: Refund result", "PSP"),
    "Msg_Cancellation":          ("HPAS: Cancellation reported", "Patient"),
    "Msg_Enquiry":               ("HPAS: Patient enquiry received", "Patient"),
    "Msg_ClinicLetter":          ("HPAS: Clinic letter required", None),
    "Msg_LetterDispatch":        ("HPAS: Letter dispatch", "Correspondence"),
    "Msg_TreatmentChange":       ("HPAS: Treatment change requested", None),
    "Msg_RefundCase":            ("HPAS: Refund case opened", None),
    "Msg_TreatmentPlanUpdated":  ("HPAS: Treatment plan updated", None),
    "Msg_NextCycleDue":          ("HPAS: Next cycle due", None),
    "Msg_FollowUpInstruction":   ("HPAS: Follow-up instruction authorised", None),
    "Msg_MonitoringRun":         ("HPAS: Monitoring run due", None),
    "Msg_AccessInterruption":    ("HPAS: Access or interruption event", None),
    "Msg_PathwayClosure":        ("HPAS: Pathway closure authorised", None),
}

POOLS = {  # external participants
    "Patient":        "Patient",
    "Referrer":       "Referring Organisation",
    "Scheduling":     "External Scheduling Service",
    "Correspondence": "External Correspondence Service",
    "PSP":            "Payment Service Provider",
    "Funder":         "Insurer / Funding Organisation",
    "ExtTreat":       "External Treatment / Laboratory / Imaging Service",
}
HOSPITAL = "Hospital Patient Administration System"

# ----------------------------------------------------------------------------
# model containers
# ----------------------------------------------------------------------------
NODES = {}   # id -> dict(kind,name,scope,lane,msg,job,cg,pool,isInt,att,timer)
FLOWS = []   # (id,scope,src,tgt,name,cond)
MFS = []     # (id,src,tgt,msg)
_f = [0]


def N(nid, scope, kind, name, lane=None, msg=None, job=None, cg=None, pool=None,
      isInt=None, att=None, timer=None):
    NODES[nid] = dict(id=nid, scope=scope, kind=kind, name=name, lane=lane, msg=msg,
                      job=job, cg=cg, pool=pool, isInt=isInt, att=att, timer=timer)
    return nid


def F(scope, src, tgt, name=None, cond=None):
    _f[0] += 1
    fid = "Flow_%03d" % _f[0]
    FLOWS.append(dict(id=fid, scope=scope, src=src, tgt=tgt, name=name or "", cond=cond or ""))
    return fid


def MF(src, tgt, msg):
    mid = "MF_%s_%s" % (src, tgt)
    MFS.append(dict(id=mid, src=src, tgt=tgt, msg=msg))
    return mid


# ============================================================================
# TOP LEVEL (coordination lane)
# ============================================================================
TOP = "TOP"
N("Top_Start", TOP, "startEvent", "Referral case opened")
N("Top_Catch_Referral", TOP, "catchEvent", "Receive referral from referring organisation", msg="Msg_Referral")
N("SP01", TOP, "subProcess", "P01 Receive and check referral")
N("SP02", TOP, "subProcess", "P02 Consultant clinical review")
N("GW_SP02_Exit", TOP, "exclusiveGateway", "Referral accepted?")
N("Top_End_ReferralClosed", TOP, "endEvent", "Referral closed (declined / redirected)")
N("SP03", TOP, "subProcess", "P03 Arrange new patient appointment")
N("GW_SP03_Exit", TOP, "exclusiveGateway", "Visit outcome?")
N("SP04", TOP, "subProcess", "P04 Assessment, diagnosis and consent")
N("GW_SP04_Exit", TOP, "exclusiveGateway", "Consent to treatment?")
N("SP05", TOP, "subProcess", "P05 Arrange treatment and external services")
N("SP06", TOP, "subProcess", "P06 Verify funding and cost allocation")
N("GW_SP06_Exit", TOP, "exclusiveGateway", "Advance payment required?")
N("SP07", TOP, "subProcess", "P07 Process payment")
N("SP08", TOP, "subProcess", "P08 Treatment cycles and continuation review")
N("Top_WaitClosure", TOP, "catchEvent", "Wait for authorised pathway closure", msg="Msg_PathwayClosure")
N("Top_NotifyClosure", TOP, "sendTask", "Notify referring organisation of pathway closure",
  job="hpas-notify-pathway-closure", msg="Msg_ReferralOutcome", pool="Referrer")
N("Top_End_Closed", TOP, "endEvent", "Patient pathway closed")
F(TOP, "Top_Start", "Top_Catch_Referral")
F(TOP, "Top_Catch_Referral", "SP01")
F(TOP, "SP01", "SP02")
F(TOP, "SP02", "GW_SP02_Exit")
F(TOP, "GW_SP02_Exit", "SP03", "accepted", '= reviewDecision = "accept"')
F(TOP, "GW_SP02_Exit", "Top_End_ReferralClosed", "not accepted", '= reviewDecision != "accept"')
F(TOP, "SP03", "GW_SP03_Exit")
F(TOP, "GW_SP03_Exit", "SP04", "attended", '= sp03Outcome = "attended"')
F(TOP, "GW_SP03_Exit", "Top_End_Closed", "closed", '= sp03Outcome = "closed"')
F(TOP, "SP04", "GW_SP04_Exit")
F(TOP, "GW_SP04_Exit", "SP05", "consent", "= consentGiven = true")
F(TOP, "GW_SP04_Exit", "Top_WaitClosure", "no treatment", "= consentGiven = false")
F(TOP, "SP05", "SP06")
F(TOP, "SP06", "GW_SP06_Exit")
F(TOP, "GW_SP06_Exit", "SP07", "payment", '= fundingRoute = "patient-pay"')
F(TOP, "GW_SP06_Exit", "SP08", "no payment", '= fundingRoute != "patient-pay"')
F(TOP, "SP07", "SP08")
F(TOP, "SP08", "Top_WaitClosure")
F(TOP, "Top_WaitClosure", "Top_NotifyClosure")
F(TOP, "Top_NotifyClosure", "Top_End_Closed")

# ============================================================================
# SP01 - Receive and check referral (lane: Medical Secretaries)
# ============================================================================
N("SP01_Start", "SP01", "startEvent", "")
N("SP01_CheckDocuments", "SP01", "userTask", "Check referral supporting documents",
  lane="Medical Secretaries", cg="medical-secretary")
N("SP01_GW_Complete", "SP01", "exclusiveGateway", "Documents complete?")
N("SP01_RequestMissing", "SP01", "sendTask", "Request missing information from referrer",
  lane="Medical Secretaries", job="hpas-sp01-request-missing-info", msg="Msg_MissingInfoRequest", pool="Referrer")
N("SP01_Catch_Supplementary", "SP01", "catchEvent", "Supplementary information received", msg="Msg_Supplementary")
N("SP01_End", "SP01", "endEvent", "Referral ready for clinical review")
F("SP01", "SP01_Start", "SP01_CheckDocuments")
F("SP01", "SP01_CheckDocuments", "SP01_GW_Complete")
F("SP01", "SP01_GW_Complete", "SP01_End", "complete", "= documentsComplete = true")
F("SP01", "SP01_GW_Complete", "SP01_RequestMissing", "missing", "= documentsComplete = false")
F("SP01", "SP01_RequestMissing", "SP01_Catch_Supplementary")
F("SP01", "SP01_Catch_Supplementary", "SP01_CheckDocuments")

# ============================================================================
# SP02 - Consultant clinical review (lane: Consultant)
# ============================================================================
N("SP02_Start", "SP02", "startEvent", "")
N("SP02_ClinicalReview", "SP02", "userTask", "Review referral and record decision",
  lane="Consultants", cg="consultant")
N("SP02_GW_Decision", "SP02", "exclusiveGateway", "Consultant decision?")
N("SP02_NotifyRejected", "SP02", "sendTask", "Send rejection with reason and decision-maker",
  lane="Consultants", job="hpas-sp02-notify-rejection", msg="Msg_ReferralOutcome", pool="Referrer")
N("SP02_RequestInfo", "SP02", "sendTask", "Request further clinical information",
  lane="Consultants", job="hpas-sp02-request-info", msg="Msg_MissingInfoRequest", pool="Referrer")
N("SP02_Catch_Info", "SP02", "catchEvent", "Further information received", msg="Msg_Supplementary")
N("SP02_NotifyRedirected", "SP02", "sendTask", "Notify redirection to another specialist service",
  lane="Consultants", job="hpas-sp02-notify-redirect", msg="Msg_ReferralOutcome", pool="Referrer")
N("SP02_End_Accepted", "SP02", "endEvent", "Referral accepted")
N("SP02_End_Rejected", "SP02", "endEvent", "Referral rejected")
N("SP02_End_Redirected", "SP02", "endEvent", "Referral redirected")
F("SP02", "SP02_Start", "SP02_ClinicalReview")
F("SP02", "SP02_ClinicalReview", "SP02_GW_Decision")
F("SP02", "SP02_GW_Decision", "SP02_End_Accepted", "accept", '= reviewDecision = "accept"')
F("SP02", "SP02_GW_Decision", "SP02_NotifyRejected", "reject", '= reviewDecision = "reject"')
F("SP02", "SP02_GW_Decision", "SP02_RequestInfo", "information", '= reviewDecision = "information"')
F("SP02", "SP02_GW_Decision", "SP02_NotifyRedirected", "redirect", '= reviewDecision = "redirect"')
F("SP02", "SP02_NotifyRejected", "SP02_End_Rejected")
F("SP02", "SP02_RequestInfo", "SP02_Catch_Info")
F("SP02", "SP02_Catch_Info", "SP02_ClinicalReview")
F("SP02", "SP02_NotifyRedirected", "SP02_End_Redirected")

# ============================================================================
# SP03 - Arrange new patient appointment (lanes: Outpatient Bookings / Call Handling)
# ============================================================================
N("SP03_Start", "SP03", "startEvent", "")
N("SP03_GW_Urgent", "SP03", "exclusiveGateway", "Urgent referral?")
N("SP03_FastTrack", "SP03", "serviceTask", "Flag priority and expedite timescales",
  lane="Outpatient Bookings Team", job="hpas-sp03-flag-urgent")
N("SP03_RequestSlot", "SP03", "sendTask", "Search appointment via external scheduling service",
  lane="Outpatient Bookings Team", job="hpas-sp03-search-slot", msg="Msg_SlotSearch", pool="Scheduling")
N("SP03_Catch_Slot", "SP03", "catchEvent", "Scheduling result received", msg="Msg_SchedulingResult")
N("SP03_GW_Slot", "SP03", "exclusiveGateway", "Slot within requested period?")
N("SP03_NoSlotReview", "SP03", "userTask", "No slot in period: highlight and refer to pathway team",
  lane="Outpatient Bookings Team", cg="pathway-coordinator")
N("SP03_BookNextAvailable", "SP03", "serviceTask", "Book next available slot with recorded exception",
  lane="Outpatient Bookings Team", job="hpas-sp03-book-next-available")
N("SP03_ConfirmBooking", "SP03", "serviceTask", "Confirm booking in system",
  lane="Outpatient Bookings Team", job="hpas-sp03-confirm-booking")
N("SP03_GW_TwoWeek", "SP03", "exclusiveGateway", "Appointment within two weeks?")
N("SP03_PhoneContact", "SP03", "userTask", "Telephone the patient and record outcome",
  lane="Call Handling Team", cg="call-handling")
N("SP03_GW_Phone", "SP03", "exclusiveGateway", "Contact outcome?")
N("SP03_GW_Attempts", "SP03", "exclusiveGateway", "Attempts < 3?")
N("SP03_EscalateContact", "SP03", "userTask", "Escalate failed contact to Pathway Coordinator",
  lane="Outpatient Bookings Team", cg="pathway-coordinator")
N("SP03_SendLetter", "SP03", "sendTask", "Send appointment letter via correspondence service",
  lane="Outpatient Bookings Team", job="hpas-sp03-send-appointment-letter", msg="Msg_AppointmentLetter", pool="Correspondence")
N("SP03_WaitVisit", "SP03", "eventBasedGateway", "Wait for clinic visit or closure")
N("SP03_Catch_Visited", "SP03", "catchEvent", "Clinic visit completed", msg="Msg_ClinicVisitCompleted")
N("SP03_Catch_Close", "SP03", "catchEvent", "Pathway closure authorised", msg="Msg_PathwayClosure")
N("SP03_End_Attended", "SP03", "endEvent", "Patient attended")
N("SP03_End_Closed", "SP03", "endEvent", "Pathway closed")
F("SP03", "SP03_Start", "SP03_GW_Urgent")
F("SP03", "SP03_GW_Urgent", "SP03_FastTrack", "urgent", "= urgentReferral = true")
F("SP03", "SP03_GW_Urgent", "SP03_RequestSlot", "routine", "= urgentReferral = false")
F("SP03", "SP03_FastTrack", "SP03_RequestSlot")
F("SP03", "SP03_RequestSlot", "SP03_Catch_Slot")
F("SP03", "SP03_Catch_Slot", "SP03_GW_Slot")
F("SP03", "SP03_GW_Slot", "SP03_ConfirmBooking", "found", "= slotFound = true")
F("SP03", "SP03_GW_Slot", "SP03_NoSlotReview", "none", "= slotFound = false")
F("SP03", "SP03_NoSlotReview", "SP03_BookNextAvailable")
F("SP03", "SP03_BookNextAvailable", "SP03_ConfirmBooking")
F("SP03", "SP03_ConfirmBooking", "SP03_GW_TwoWeek")
F("SP03", "SP03_GW_TwoWeek", "SP03_PhoneContact", "within 2 weeks", "= withinTwoWeeks = true")
F("SP03", "SP03_GW_TwoWeek", "SP03_SendLetter", "later", "= withinTwoWeeks = false")
F("SP03", "SP03_PhoneContact", "SP03_GW_Phone")
F("SP03", "SP03_GW_Phone", "SP03_SendLetter", "reached", '= contactOutcome = "reached"')
F("SP03", "SP03_GW_Phone", "SP03_GW_Attempts", "not reached", 'contactOutcome != "reached"')
F("SP03", "SP03_GW_Attempts", "SP03_PhoneContact", "retry", "= phoneAttempts < 3")
F("SP03", "SP03_GW_Attempts", "SP03_EscalateContact", "escalate", "= phoneAttempts >= 3")
F("SP03", "SP03_EscalateContact", "SP03_SendLetter")
F("SP03", "SP03_SendLetter", "SP03_WaitVisit")
F("SP03", "SP03_WaitVisit", "SP03_Catch_Visited")
F("SP03", "SP03_WaitVisit", "SP03_Catch_Close")
F("SP03", "SP03_Catch_Visited", "SP03_End_Attended")
F("SP03", "SP03_Catch_Close", "SP03_End_Closed")

# ============================================================================
# SP03B - Appointment changes (EVENT subprocess)
# ============================================================================
N("SP03B_Start", "SP03B", "startEvent", "Appointment change requested", msg="Msg_AppointmentChange", isInt=False)
N("SP03B_GW_Kind", "SP03B", "exclusiveGateway", "Change kind?")
N("SP03B_RequestSlot", "SP03B", "sendTask", "Search new slot via scheduling service", lane="Outpatient Bookings Team",
  job="hpas-sp03b-search-slot", msg="Msg_SlotSearch", pool="Scheduling")
N("SP03B_Catch_Result", "SP03B", "catchEvent", "New slot offered", msg="Msg_SchedulingResult")
N("SP03B_GW_Result", "SP03B", "exclusiveGateway", "Slot acceptable?")
N("SP03B_Rebook", "SP03B", "serviceTask", "Apply reschedule without duplicate booking", lane="Outpatient Bookings Team",
  job="hpas-sp03b-apply-reschedule")
N("SP03B_Notify", "SP03B", "sendTask", "Notify patient of changed appointment", lane="Outpatient Bookings Team",
  job="hpas-sp03b-notify-patient", msg="Msg_AppointmentLetter", pool="Correspondence")
N("SP03B_Review", "SP03B", "userTask", "Pathway team review of change request", lane="Outpatient Bookings Team", cg="outpatient-bookings")
N("SP03B_End", "SP03B", "endEvent", "Change handled")
F("SP03B", "SP03B_Start", "SP03B_GW_Kind")
F("SP03B", "SP03B_GW_Kind", "SP03B_RequestSlot", "reschedule", '= changeKind = "reschedule"')
F("SP03B", "SP03B_GW_Kind", "SP03B_Review", "other", 'changeKind != "reschedule"')
F("SP03B", "SP03B_RequestSlot", "SP03B_Catch_Result")
F("SP03B", "SP03B_Catch_Result", "SP03B_GW_Result")
F("SP03B", "SP03B_GW_Result", "SP03B_Rebook", "acceptable", "= slotAcceptable = true")
F("SP03B", "SP03B_GW_Result", "SP03B_Review", "conflict", "= slotAcceptable = false")
F("SP03B", "SP03B_Rebook", "SP03B_Notify")
F("SP03B", "SP03B_Notify", "SP03B_End")
F("SP03B", "SP03B_Review", "SP03B_End")

# ============================================================================
# SP04 - Assessment, diagnosis and consent (lane: Consultant)
# ============================================================================
N("SP04_Start", "SP04", "startEvent", "")
N("SP04_Assess", "SP04", "userTask", "Assess patient, discuss diagnosis and options",
  lane="Consultants", cg="consultant")
N("SP04_GW_Consent", "SP04", "exclusiveGateway", "Patient consents to treatment?")
N("SP04_RecordConsent", "SP04", "userTask", "Record informed consent", lane="Consultants", cg="consultant")
N("SP04_CreateRequest", "SP04", "userTask", "Create authorised Treatment Booking Request",
  lane="Consultants", cg="consultant")
N("SP04_Validate", "SP04", "serviceTask", "Validate request completeness and clinical authorisation",
  lane="Consultants", job="hpas-sp04-validate-request")
N("SP04_GW_Valid", "SP04", "exclusiveGateway", "Request valid?")
N("SP04_NotifyLetter", "SP04", "sendTask", "Trigger clinic letter preparation",
  lane="Consultants", job="hpas-notify-clinic-letter", msg="Msg_ClinicLetter")
N("SP04_GW_AfterLetter", "SP04", "exclusiveGateway", "Treatment agreed?")
N("SP04_End_Consent", "SP04", "endEvent", "Treatment authorised")
N("SP04_End_NoTreatment", "SP04", "endEvent", "No treatment agreed")
F("SP04", "SP04_Start", "SP04_Assess")
F("SP04", "SP04_Assess", "SP04_GW_Consent")
F("SP04", "SP04_GW_Consent", "SP04_RecordConsent", "consent", "= consentGiven = true")
F("SP04", "SP04_GW_Consent", "SP04_NotifyLetter", "no consent", "= consentGiven = false")
F("SP04", "SP04_RecordConsent", "SP04_CreateRequest")
F("SP04", "SP04_CreateRequest", "SP04_Validate")
F("SP04", "SP04_Validate", "SP04_GW_Valid")
F("SP04", "SP04_GW_Valid", "SP04_NotifyLetter", "valid", "= requestValid = true")
F("SP04", "SP04_GW_Valid", "SP04_CreateRequest", "invalid", "= requestValid = false")
F("SP04", "SP04_NotifyLetter", "SP04_GW_AfterLetter")
F("SP04", "SP04_GW_AfterLetter", "SP04_End_Consent", "yes", "= consentGiven = true")
F("SP04", "SP04_GW_AfterLetter", "SP04_End_NoTreatment", "no", "= consentGiven = false")

# ============================================================================
# SP05 - Arrange treatment and external services (lane: Treatment Bookings)
# ============================================================================
N("SP05_Start", "SP05", "startEvent", "")
N("SP05_GW_Resource", "SP05", "exclusiveGateway", "Resource type?")
N("SP05_BookInternal", "SP05", "serviceTask", "Book hospital facility",
  lane="Treatment & Chemotherapy Bookings Team", job="hpas-sp05-book-internal")
N("SP05_RequestAvailability", "SP05", "sendTask", "Request availability from external service",
  lane="Treatment & Chemotherapy Bookings Team", job="hpas-sp05-request-availability", msg="Msg_ExternalAvailability", pool="ExtTreat")
N("SP05_Catch_Availability", "SP05", "catchEvent", "Availability result received", msg="Msg_ExternalAvailabilityResult")
N("SP05_GW_Available", "SP05", "exclusiveGateway", "External slot available?")
N("SP05_BookExternal", "SP05", "serviceTask", "Create provisional booking with external service",
  lane="Treatment & Chemotherapy Bookings Team", job="hpas-sp05-book-external")
N("SP05_NotifyPending", "SP05", "serviceTask", "Notify responsible team, keep pending (no duplicates)",
  lane="Treatment & Chemotherapy Bookings Team", job="hpas-sp05-notify-pending")
N("SP05_Catch_Released", "SP05", "catchEvent", "External service available again", msg="Msg_ExternalSlotReleased")
N("SP05_End_Booked", "SP05", "endEvent", "Treatment provisionally booked")
F("SP05", "SP05_Start", "SP05_GW_Resource")
F("SP05", "SP05_GW_Resource", "SP05_BookInternal", "hospital", '= resourceType = "internal"')
F("SP05", "SP05_GW_Resource", "SP05_RequestAvailability", "external", '= resourceType = "external"')
F("SP05", "SP05_BookInternal", "SP05_End_Booked")
F("SP05", "SP05_RequestAvailability", "SP05_Catch_Availability")
F("SP05", "SP05_Catch_Availability", "SP05_GW_Available")
F("SP05", "SP05_GW_Available", "SP05_BookExternal", "available", "= externalAvailable = true")
F("SP05", "SP05_GW_Available", "SP05_NotifyPending", "unavailable", "= externalAvailable = false")
F("SP05", "SP05_BookExternal", "SP05_End_Booked")
F("SP05", "SP05_NotifyPending", "SP05_Catch_Released")
F("SP05", "SP05_Catch_Released", "SP05_GW_Available")

# ============================================================================
# SP06 - Verify funding and cost allocation (lane: Finance)
# ============================================================================
N("SP06_Start", "SP06", "startEvent", "")
N("SP06_Determine", "SP06", "serviceTask", "Determine funding route",
  lane="Finance Team", job="hpas-sp06-determine-funding")
N("SP06_GW_Route", "SP06", "exclusiveGateway", "Funding route?")
N("SP06_RecordExemption", "SP06", "serviceTask", "Record authorised exemption",
  lane="Finance Team", job="hpas-sp06-record-exemption")
N("SP06_RequestApproval", "SP06", "sendTask", "Request funding approval from insurer",
  lane="Finance Team", job="hpas-sp06-request-approval", msg="Msg_FundingApprovalRequest", pool="Funder")
N("SP06_Catch_Approval", "SP06", "catchEvent", "Funding approval received", msg="Msg_FundingApproval")
N("SP06_RecordApproval", "SP06", "userTask", "Record organisation, authorisation reference, amount, limits",
  lane="Finance Team", cg="finance")
N("SP06_End_NoPayment", "SP06", "endEvent", "No advance payment required")
N("SP06_End_PaymentRequired", "SP06", "endEvent", "Advance payment required")
F("SP06", "SP06_Start", "SP06_Determine")
F("SP06", "SP06_Determine", "SP06_GW_Route")
F("SP06", "SP06_GW_Route", "SP06_End_NoPayment", "hospital", '= fundingRoute = "hospital"')
F("SP06", "SP06_GW_Route", "SP06_RecordExemption", "exempt", '= fundingRoute = "exempt"')
F("SP06", "SP06_GW_Route", "SP06_RequestApproval", "insurer", '= fundingRoute = "insurer"')
F("SP06", "SP06_GW_Route", "SP06_End_PaymentRequired", "patient-pay", '= fundingRoute = "patient-pay"')
F("SP06", "SP06_RecordExemption", "SP06_End_NoPayment")
F("SP06", "SP06_RequestApproval", "SP06_Catch_Approval")
F("SP06", "SP06_Catch_Approval", "SP06_RecordApproval")
F("SP06", "SP06_RecordApproval", "SP06_End_NoPayment")

# ============================================================================
# SP07 - Process payment (lane: Finance)
# ============================================================================
N("SP07_Start", "SP07", "startEvent", "")
N("SP07_CalculateCharge", "SP07", "serviceTask", "Calculate or obtain applicable charge",
  lane="Finance Team", job="hpas-sp07-calculate-charge")
N("SP07_SendPaymentRequest", "SP07", "sendTask", "Send secure payment request to PSP",
  lane="Finance Team", job="hpas-sp07-send-payment-request", msg="Msg_PaymentRequest", pool="PSP")
N("SP07_Catch_Status", "SP07", "catchEvent", "Payment status received", msg="Msg_PaymentStatus")
N("SP07_GW_Status", "SP07", "exclusiveGateway", "Payment status?")
N("SP07_RecordTransaction", "SP07", "serviceTask", "Record status, reference, date and amount (no card data)",
  lane="Finance Team", job="hpas-sp07-record-transaction")
N("SP07_ConfirmBooking", "SP07", "serviceTask", "Confirm treatment booking (payment complete)",
  lane="Finance Team", job="hpas-sp07-confirm-booking")
N("SP07_NotifyIssue", "SP07", "sendTask", "Notify patient and responsible team of payment issue",
  lane="Finance Team", job="hpas-sp07-notify-issue", msg="Msg_PaymentIssueNotice", pool="Correspondence")
N("SP07_GW_Retry", "SP07", "exclusiveGateway", "How to proceed?")
N("SP07_RecordOverride", "SP07", "userTask", "Record reason for proceeding without confirmed payment",
  lane="Finance Team", cg="consultant")
N("SP07_ReferFinance", "SP07", "serviceTask", "Refer to Finance Team for subsequent resolution",
  lane="Finance Team", job="hpas-sp07-refer-finance")
N("SP07_MarkInvestigation", "SP07", "serviceTask", "Mark transaction for investigation (no automatic re-request)",
  lane="Finance Team", job="hpas-sp07-mark-investigation")
N("SP07_Investigate", "SP07", "userTask", "Investigate transaction with PSP", lane="Finance Team", cg="finance")
N("SP07_GW_Investigation", "SP07", "exclusiveGateway", "Investigation outcome?")
N("SP07_End_Paid", "SP07", "endEvent", "Payment complete, booking confirmed")
N("SP07_End_Override", "SP07", "endEvent", "Urgent treatment authorised without confirmed payment")
F("SP07", "SP07_Start", "SP07_CalculateCharge")
F("SP07", "SP07_CalculateCharge", "SP07_SendPaymentRequest")
F("SP07", "SP07_SendPaymentRequest", "SP07_Catch_Status")
F("SP07", "SP07_Catch_Status", "SP07_GW_Status")
F("SP07", "SP07_GW_Status", "SP07_RecordTransaction", "completed", '= paymentStatus = "completed"')
F("SP07", "SP07_GW_Status", "SP07_MarkInvestigation", "confirmation lost", '= paymentStatus = "confirmation-lost"')
F("SP07", "SP07_GW_Status", "SP07_NotifyIssue", "declined / incomplete", 'paymentStatus != "completed" and paymentStatus != "confirmation-lost"')
F("SP07", "SP07_RecordTransaction", "SP07_ConfirmBooking")
F("SP07", "SP07_ConfirmBooking", "SP07_End_Paid")
F("SP07", "SP07_NotifyIssue", "SP07_GW_Retry")
F("SP07", "SP07_GW_Retry", "SP07_SendPaymentRequest", "new attempt", '= retryDecision = "retry"')
F("SP07", "SP07_GW_Retry", "SP07_RecordOverride", "urgent override", '= retryDecision = "urgent-override"')
F("SP07", "SP07_RecordOverride", "SP07_ReferFinance")
F("SP07", "SP07_ReferFinance", "SP07_End_Override")
F("SP07", "SP07_MarkInvestigation", "SP07_Investigate")
F("SP07", "SP07_Investigate", "SP07_GW_Investigation")
F("SP07", "SP07_GW_Investigation", "SP07_RecordTransaction", "taken", '= investigationOutcome = "taken"')
F("SP07", "SP07_GW_Investigation", "SP07_SendPaymentRequest", "not taken", '= investigationOutcome = "not-taken"')

# ============================================================================
# SP08 - Treatment cycles and continuation review (lane: Clinical Team)
# ============================================================================
N("SP08_Start", "SP08", "startEvent", "")
N("SP08_RequestBloods", "SP08", "sendTask", "Request pre-cycle blood test",
  lane="Clinical Team", job="hpas-sp08-request-bloods", msg="Msg_LabRequest", pool="ExtTreat")
N("SP08_Catch_Lab", "SP08", "catchEvent", "Blood test results received", msg="Msg_LabResult")
N("SP08_Review", "SP08", "userTask", "Clinical review of fitness to continue",
  lane="Clinical Team", cg="clinical-team")
N("SP08_GW_Fit", "SP08", "exclusiveGateway", "Fit to continue?")
N("SP08_Administer", "SP08", "serviceTask", "Record cycle administration",
  lane="Clinical Team", job="hpas-sp08-record-cycle")
N("SP08_GW_More", "SP08", "exclusiveGateway", "More cycles required?")
N("SP08_Catch_NextDue", "SP08", "catchEvent", "Next cycle due", msg="Msg_NextCycleDue")
N("SP08_DelayHandling", "SP08", "userTask", "Record treatment delay decision",
  lane="Clinical Team", cg="clinical-team")
N("SP08_NotifyChange", "SP08", "sendTask", "Submit treatment change for processing",
  lane="Clinical Team", job="hpas-sp08-notify-change", msg="Msg_TreatmentChange")
N("SP08_Catch_PlanUpdated", "SP08", "catchEvent", "Treatment plan updated", msg="Msg_TreatmentPlanUpdated")
N("SP08_NotifyLetter", "SP08", "sendTask", "Trigger clinic letter (treatment review)",
  lane="Clinical Team", job="hpas-notify-clinic-letter", msg="Msg_ClinicLetter")
N("SP08_End_Complete", "SP08", "endEvent", "Treatment course completed")
F("SP08", "SP08_Start", "SP08_RequestBloods")
F("SP08", "SP08_RequestBloods", "SP08_Catch_Lab")
F("SP08", "SP08_Catch_Lab", "SP08_Review")
F("SP08", "SP08_Review", "SP08_GW_Fit")
F("SP08", "SP08_GW_Fit", "SP08_Administer", "fit", '= fitnessDecision = "fit"')
F("SP08", "SP08_GW_Fit", "SP08_DelayHandling", "delay", '= fitnessDecision = "delay"')
F("SP08", "SP08_GW_Fit", "SP08_NotifyChange", "change plan", '= fitnessDecision = "change"')
F("SP08", "SP08_Administer", "SP08_GW_More")
F("SP08", "SP08_GW_More", "SP08_Catch_NextDue", "more", "= moreCycles = true")
F("SP08", "SP08_GW_More", "SP08_NotifyLetter", "complete", "= moreCycles = false")
F("SP08", "SP08_Catch_NextDue", "SP08_RequestBloods")
F("SP08", "SP08_DelayHandling", "SP08_Catch_NextDue")
F("SP08", "SP08_NotifyChange", "SP08_Catch_PlanUpdated")
F("SP08", "SP08_Catch_PlanUpdated", "SP08_GW_More")
F("SP08", "SP08_NotifyLetter", "SP08_End_Complete")

# ============================================================================
# SP09 - Treatment changes (EVENT)
# ============================================================================
N("SP09_Start", "SP09", "startEvent", "Treatment change requested", msg="Msg_TreatmentChange", isInt=False)
N("SP09_GW_Urgency", "SP09", "exclusiveGateway", "Urgent postponement?")
N("SP09_ExecuteHold", "SP09", "serviceTask", "Execute immediate postponement (patient safety)", lane="Clinical Team",
  job="hpas-sp09-execute-hold")
N("SP09_Retrospective", "SP09", "userTask", "Record and authorise decision retrospectively", lane="Consultants", cg="consultant")
N("SP09_ValidateFormal", "SP09", "serviceTask", "Validate formal authorisation and traceability", lane="Clinical Team",
  job="hpas-sp09-validate-formal")
N("SP09_GW_Formal", "SP09", "exclusiveGateway", "Formally authorised?")
N("SP09_ApplyChange", "SP09", "serviceTask", "Apply treatment modification", lane="Clinical Team", job="hpas-sp09-apply-change")
N("SP09_NotifyPlanUpdated", "SP09", "sendTask", "Notify updated treatment plan", lane="Clinical Team",
  job="hpas-sp09-notify-plan", msg="Msg_TreatmentPlanUpdated")
N("SP09_GW_Financial", "SP09", "exclusiveGateway", "Affects charges / funding / payment?")
N("SP09_FinanceReview", "SP09", "userTask", "Review financial implications", lane="Finance Team", cg="finance")
N("SP09_GW_Refund", "SP09", "exclusiveGateway", "Refund or transfer required?")
N("SP09_NotifyRefund", "SP09", "sendTask", "Open refund case", job="hpas-notify-refund-case", msg="Msg_RefundCase")
N("SP09_End", "SP09", "endEvent", "Change processed")
N("SP09_End_Rejected", "SP09", "endEvent", "Informal request not processed")
F("SP09", "SP09_Start", "SP09_GW_Urgency")
F("SP09", "SP09_GW_Urgency", "SP09_ExecuteHold", "urgent", "= urgentChange = true")
F("SP09", "SP09_GW_Urgency", "SP09_ValidateFormal", "normal", "= urgentChange = false")
F("SP09", "SP09_ExecuteHold", "SP09_Retrospective")
F("SP09", "SP09_Retrospective", "SP09_ApplyChange")
F("SP09", "SP09_ValidateFormal", "SP09_GW_Formal")
F("SP09", "SP09_GW_Formal", "SP09_ApplyChange", "valid", "= formallyAuthorised = true")
F("SP09", "SP09_GW_Formal", "SP09_End_Rejected", "invalid", "= formallyAuthorised = false")
F("SP09", "SP09_ApplyChange", "SP09_NotifyPlanUpdated")
F("SP09", "SP09_NotifyPlanUpdated", "SP09_GW_Financial")
F("SP09", "SP09_GW_Financial", "SP09_FinanceReview", "yes", "= financialImpact = true")
F("SP09", "SP09_GW_Financial", "SP09_End", "no", "= financialImpact = false")
F("SP09", "SP09_FinanceReview", "SP09_GW_Refund")
F("SP09", "SP09_GW_Refund", "SP09_NotifyRefund", "yes", "= refundRequired = true")
F("SP09", "SP09_GW_Refund", "SP09_End", "no", "= refundRequired = false")
F("SP09", "SP09_NotifyRefund", "SP09_End")

# ============================================================================
# SP10 - Cancellation / refusal / no-show (EVENT)
# ============================================================================
N("SP10_Start", "SP10", "startEvent", "Cancellation, refusal or no-show reported", msg="Msg_Cancellation", isInt=False)
N("SP10_Record", "SP10", "userTask", "Record cancellation / refusal / non-attendance event", lane="Call Handling Team",
  cg="call-handling")
N("SP10_GW_Paid", "SP10", "exclusiveGateway", "Paid appointment affected?")
N("SP10_NotifyRefund", "SP10", "sendTask", "Refer to Finance for retain / transfer / refund", lane="Finance Team",
  job="hpas-notify-refund-case", msg="Msg_RefundCase")
N("SP10_Decide", "SP10", "userTask", "Decide pathway response (clinical responsibility)", lane="Consultants", cg="consultant")
N("SP10_GW_Response", "SP10", "exclusiveGateway", "Pathway response?")
N("SP10_NotifyRebook", "SP10", "sendTask", "Request re-booking", lane="Call Handling Team", job="hpas-sp10-notify-rebook",
  msg="Msg_AppointmentChange")
N("SP10_ClinicalReview", "SP10", "userTask", "Review patient pathway", lane="Clinical Team", cg="clinical-team")
N("SP10_NotifyReferrer", "SP10", "sendTask", "Inform referring organisation", lane="Call Handling Team",
  job="hpas-sp10-notify-referrer", msg="Msg_ReferralOutcome", pool="Referrer")
N("SP10_End", "SP10", "endEvent", "Cancellation handled")
F("SP10", "SP10_Start", "SP10_Record")
F("SP10", "SP10_Record", "SP10_GW_Paid")
F("SP10", "SP10_GW_Paid", "SP10_NotifyRefund", "paid", "= appointmentPaid = true")
F("SP10", "SP10_GW_Paid", "SP10_Decide", "unpaid", "= appointmentPaid = false")
F("SP10", "SP10_NotifyRefund", "SP10_Decide")
F("SP10", "SP10_Decide", "SP10_GW_Response")
F("SP10", "SP10_GW_Response", "SP10_NotifyRebook", "re-offer", '= pathwayResponse = "re-offer"')
F("SP10", "SP10_GW_Response", "SP10_ClinicalReview", "clinical review", '= pathwayResponse = "clinical-review"')
F("SP10", "SP10_GW_Response", "SP10_NotifyReferrer", "close", '= pathwayResponse = "close"')
F("SP10", "SP10_NotifyRebook", "SP10_End")
F("SP10", "SP10_ClinicalReview", "SP10_End")
F("SP10", "SP10_NotifyReferrer", "SP10_End")

# ============================================================================
# SP11 - Refunds (EVENT)
# ============================================================================
N("SP11_Start", "SP11", "startEvent", "Refund case opened", msg="Msg_RefundCase", isInt=False)
N("SP11_Determine", "SP11", "userTask", "Determine full / partial refund (financial authority)", lane="Finance Team",
  cg="finance")
N("SP11_GW_Scope", "SP11", "exclusiveGateway", "Refund appropriate?")
N("SP11_SendRefund", "SP11", "sendTask", "Send approved refund request to PSP",
  job="hpas-sp11-send-refund", msg="Msg_RefundRequest", pool="PSP")
N("SP11_Catch_Result", "SP11", "catchEvent", "Refund result received", msg="Msg_RefundResult")
N("SP11_Record", "SP11", "serviceTask", "Record refund against patient account",
  job="hpas-sp11-record-refund")
N("SP11_RecordNone", "SP11", "serviceTask", "Record no-refund decision with reason",
  job="hpas-sp11-record-no-refund")
N("SP11_End", "SP11", "endEvent", "Refund case processed")
F("SP11", "SP11_Start", "SP11_Determine")
F("SP11", "SP11_Determine", "SP11_GW_Scope")
F("SP11", "SP11_GW_Scope", "SP11_SendRefund", "refund due", "= refundDue = true")
F("SP11", "SP11_GW_Scope", "SP11_RecordNone", "no refund", "= refundDue = false")
F("SP11", "SP11_SendRefund", "SP11_Catch_Result")
F("SP11", "SP11_Catch_Result", "SP11_Record")
F("SP11", "SP11_Record", "SP11_End")
F("SP11", "SP11_RecordNone", "SP11_End")

# ============================================================================
# SP12 - Patient enquiries (EVENT)
# ============================================================================
N("SP12_Start", "SP12", "startEvent", "Patient enquiry received", msg="Msg_Enquiry", isInt=False)
N("SP12_Record", "SP12", "userTask", "Record and classify enquiry", lane="Call Handling Team", cg="call-handling")
N("SP12_GW_Class", "SP12", "exclusiveGateway", "Enquiry classification?")
N("SP12_Answer", "SP12", "userTask", "Answer administrative question directly", lane="Call Handling Team", cg="call-handling")
N("SP12_Transfer", "SP12", "userTask", "Transfer to responsible administrative team", lane="Call Handling Team", cg="call-handling")
N("SP12_GW_Info", "SP12", "exclusiveGateway", "Answerable with authorised information?")
N("SP12_AnswerFin", "SP12", "userTask", "Answer financial enquiry from authorised information", lane="Call Handling Team",
  cg="call-handling")
N("SP12_ReferFinance", "SP12", "userTask", "Respond to financial enquiry", lane="Finance Team", cg="finance")
N("SP12_FlagUrgent", "SP12", "serviceTask", "Flag urgent clinical concern (type-based auto-classification)", lane="Call Handling Team",
  job="hpas-sp12-flag-urgent")
N("SP12_ReferCNS", "SP12", "userTask", "Respond to clinical enquiry (CNS team)", lane="Clinical Nurse Specialist Team", cg="cns")
N("SP12_Close", "SP12", "serviceTask", "Record handling, team assignment, response, resolution", lane="Call Handling Team",
  job="hpas-sp12-close-record")
N("SP12_End", "SP12", "endEvent", "Enquiry handled")
F("SP12", "SP12_Start", "SP12_Record")
F("SP12", "SP12_Record", "SP12_GW_Class")
F("SP12", "SP12_GW_Class", "SP12_Answer", "admin simple", '= enquiryClass = "admin-simple"')
F("SP12", "SP12_GW_Class", "SP12_Transfer", "admin transfer", '= enquiryClass = "admin-transfer"')
F("SP12", "SP12_GW_Class", "SP12_GW_Info", "financial", '= enquiryClass = "financial"')
F("SP12", "SP12_GW_Class", "SP12_FlagUrgent", "clinical", '= enquiryClass = "clinical"')
F("SP12", "SP12_Answer", "SP12_Close")
F("SP12", "SP12_Transfer", "SP12_Close")
F("SP12", "SP12_GW_Info", "SP12_AnswerFin", "authorised info", "= infoAuthorised = true")
F("SP12", "SP12_GW_Info", "SP12_ReferFinance", "beyond authorised info", "= infoAuthorised = false")
F("SP12", "SP12_AnswerFin", "SP12_Close")
F("SP12", "SP12_ReferFinance", "SP12_Close")
F("SP12", "SP12_FlagUrgent", "SP12_ReferCNS")
F("SP12", "SP12_ReferCNS", "SP12_Close")
F("SP12", "SP12_Close", "SP12_End")

# ============================================================================
# SP13 - Clinic letters (EVENT; contains nested letter-preparation scope + 7d timer)
# ============================================================================
N("SP13_Start", "SP13", "startEvent", "Clinic letter required", msg="Msg_ClinicLetter", isInt=False)
N("SP13_PrepareScope", "SP13", "subProcess", "Letter preparation (7-day SLA)", lane="Consultants")
N("SP13_PS_Start", "SP13_PS", "startEvent", "")
N("SP13_Draft", "SP13_PS", "userTask", "Prepare clinic letter (clinical content)", lane="Consultants", cg="consultant")
N("SP13_Approve", "SP13_PS", "userTask", "Approve clinical content", lane="Consultants", cg="consultant")
N("SP13_PS_End", "SP13_PS", "endEvent", "Letter prepared")
F("SP13_PS", "SP13_PS_Start", "SP13_Draft")
F("SP13_PS", "SP13_Draft", "SP13_Approve")
F("SP13_PS", "SP13_Approve", "SP13_PS_End")
N("SP13_Timer7d", "SP13", "boundaryEvent", "Overdue after 7 days", att="SP13_PrepareScope", timer="P7D")
N("SP13_Reminder", "SP13", "userTask", "Issue weekly reminder to Consultant", lane="Patient Pathway Coordinators", cg="pathway-coordinator")
N("SP13_End_Reminder", "SP13", "endEvent", "Reminder issued")
N("SP13_AdminProcess", "SP13", "userTask", "Administrative checks, confirm recipients", lane="Medical Secretaries", cg="medical-secretary")
N("SP13_GW_Admin", "SP13", "exclusiveGateway", "Check outcome?")
N("SP13_Distribute", "SP13", "sendTask", "Arrange distribution via approved channels", lane="Medical Secretaries",
  job="hpas-sp13-distribute", msg="Msg_LetterDispatch", pool="Correspondence")
N("SP13_RecordDates", "SP13", "serviceTask", "Record dates and method of distribution", lane="Medical Secretaries",
  job="hpas-sp13-record-dates")
N("SP13_GW_FollowUp", "SP13", "exclusiveGateway", "Authorised follow-up instruction?")
N("SP13_NotifyFollowUp", "SP13", "sendTask", "Send follow-up appointment instruction", lane="Consultants",
  job="hpas-sp13-notify-follow-up", msg="Msg_FollowUpInstruction")
N("SP13_End", "SP13", "endEvent", "Letter processed")
F("SP13", "SP13_Start", "SP13_PrepareScope")
F("SP13", "SP13_PrepareScope", "SP13_AdminProcess")
F("SP13", "SP13_Timer7d", "SP13_Reminder")
F("SP13", "SP13_Reminder", "SP13_End_Reminder")
F("SP13", "SP13_AdminProcess", "SP13_GW_Admin")
F("SP13", "SP13_GW_Admin", "SP13_Distribute", "ok", '= adminCheck = "ok"')
F("SP13", "SP13_GW_Admin", "SP13_PrepareScope", "suspected clinical error", '= adminCheck = "clinical-error"')
F("SP13", "SP13_Distribute", "SP13_RecordDates")
F("SP13", "SP13_RecordDates", "SP13_GW_FollowUp")
F("SP13", "SP13_GW_FollowUp", "SP13_NotifyFollowUp", "yes", "= followUpInstructed = true")
F("SP13", "SP13_GW_FollowUp", "SP13_End", "no", "= followUpInstructed = false")
F("SP13", "SP13_NotifyFollowUp", "SP13_End")

# ============================================================================
# SP14 - Follow-up appointments (EVENT)
# ============================================================================
N("SP14_Start", "SP14", "startEvent", "Follow-up instruction authorised", msg="Msg_FollowUpInstruction", isInt=False)
N("SP14_RequestSlot", "SP14", "sendTask", "Search follow-up slot within requested period", lane="Outpatient Bookings Team",
  job="hpas-sp14-search-slot", msg="Msg_SlotSearch", pool="Scheduling")
N("SP14_Catch_Result", "SP14", "catchEvent", "Slot offered", msg="Msg_SchedulingResult")
N("SP14_GW_InPeriod", "SP14", "exclusiveGateway", "Slot within requested period?")
N("SP14_Book", "SP14", "serviceTask", "Record follow-up booking", lane="Outpatient Bookings Team", job="hpas-sp14-book-follow-up")
N("SP14_Inform", "SP14", "sendTask", "Inform patient via appropriate channel", lane="Outpatient Bookings Team",
  job="hpas-sp14-inform-patient", msg="Msg_AppointmentLetter", pool="Correspondence")
N("SP14_Highlight", "SP14", "userTask", "Highlight case and refer to pathway team", lane="Patient Pathway Coordinators",
  cg="pathway-coordinator")
N("SP14_End", "SP14", "endEvent", "Follow-up handled")
F("SP14", "SP14_Start", "SP14_RequestSlot")
F("SP14", "SP14_RequestSlot", "SP14_Catch_Result")
F("SP14", "SP14_Catch_Result", "SP14_GW_InPeriod")
F("SP14", "SP14_GW_InPeriod", "SP14_Book", "within period", "= inRequestedPeriod = true")
F("SP14", "SP14_GW_InPeriod", "SP14_Highlight", "outside period", "= inRequestedPeriod = false")
F("SP14", "SP14_Book", "SP14_Inform")
F("SP14", "SP14_Inform", "SP14_End")
F("SP14", "SP14_Highlight", "SP14_End")

# ============================================================================
# SP15 - Monitoring and reports (EVENT)
# ============================================================================
N("SP15_Start", "SP15", "startEvent", "Monitoring run or report request received", msg="Msg_MonitoringRun", isInt=False)
N("SP15_Scan", "SP15", "serviceTask", "Scan outstanding items (letters, referrals, contacts, bookings, funding, payments, refunds, enquiries)", lane="Patient Pathway Coordinators",
  job="hpas-sp15-scan-outstanding")
N("SP15_GW_Findings", "SP15", "exclusiveGateway", "Findings?")
N("SP15_EscalateManager", "SP15", "userTask", "Administrative Manager contacts responsible Consultant", lane="Administrative Management Team",
  cg="admin-manager")
N("SP15_EscalateHigher", "SP15", "userTask", "Escalate to higher management team", lane="Administrative Management Team", cg="admin-manager")
N("SP15_Generate", "SP15", "serviceTask", "Generate management reports (volumes, delays, pathway progress)", lane="Patient Pathway Coordinators",
  job="hpas-sp15-generate-reports")
N("SP15_End", "SP15", "endEvent", "Monitoring run complete")
F("SP15", "SP15_Start", "SP15_Scan")
F("SP15", "SP15_Scan", "SP15_GW_Findings")
F("SP15", "SP15_GW_Findings", "SP15_EscalateManager", "letter > 1 month",
    "= letterOverdueMonths >= 1 and letterOverdueMonths < 3")
F("SP15", "SP15_GW_Findings", "SP15_EscalateHigher", "letter > 3 months", "= letterOverdueMonths >= 3")
F("SP15", "SP15_GW_Findings", "SP15_Generate", "routine", "= letterOverdueMonths < 1")
F("SP15", "SP15_EscalateManager", "SP15_Generate")
F("SP15", "SP15_EscalateHigher", "SP15_Generate")
F("SP15", "SP15_Generate", "SP15_End")

# ============================================================================
# SP16 - Access / audit / downtime (EVENT)
# ============================================================================
N("SP16_Start", "SP16", "startEvent", "Access change or service interruption reported", msg="Msg_AccessInterruption", isInt=False)
N("SP16_GW_Kind", "SP16", "exclusiveGateway", "Event kind?")
N("SP16_VerifyAuth", "SP16", "userTask", "Verify staff identity and authorisation", lane="Administrative Management Team", cg="admin-manager")
N("SP16_ApplyAccess", "SP16", "serviceTask", "Apply role-based access change", job="hpas-sp16-apply-access")
N("SP16_RecordDowntime", "SP16", "userTask", "Record activities completed during unavailability", lane="Administrative Management Team",
  cg="admin-manager")
N("SP16_Reconcile", "SP16", "serviceTask", "Reconcile records after restoration", job="hpas-sp16-reconcile")
N("SP16_Audit", "SP16", "serviceTask", "Write audit record (user, date, time, nature)",
  job="hpas-sp16-audit")
N("SP16_End", "SP16", "endEvent", "Event handled")
F("SP16", "SP16_Start", "SP16_GW_Kind")
F("SP16", "SP16_GW_Kind", "SP16_VerifyAuth", "access change", '= eventKind = "access"')
F("SP16", "SP16_GW_Kind", "SP16_RecordDowntime", "interruption", '= eventKind = "interruption"')
F("SP16", "SP16_VerifyAuth", "SP16_ApplyAccess")
F("SP16", "SP16_ApplyAccess", "SP16_Audit")
F("SP16", "SP16_RecordDowntime", "SP16_Reconcile")
F("SP16", "SP16_Reconcile", "SP16_Audit")
F("SP16", "SP16_Audit", "SP16_End")

# ----------------------------------------------------------------------------
# external mock response processes (diagram completeness - not executable)
# ----------------------------------------------------------------------------
EXT_KIND = {"start": "startEvent", "msgstart": "startEvent", "task": "task",
            "send": "throwEvent", "gw": "exclusiveGateway", "end": "endEvent"}

EXT_PROC = {
    "Referrer": [
        dict(nodes=[("start", "XRef_Start", "", None),
                    ("task", "XRef_Prepare", "Prepare referral package", None),
                    ("send", "XRef_SendReferral", "Send referral", "Msg_Referral"),
                    ("end", "XRef_End1", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
        dict(nodes=[("msgstart", "XRef_InfoStart", "Missing information request", "Msg_MissingInfoRequest"),
                    ("task", "XRef_ProvideInfo", "Provide missing information", None),
                    ("send", "XRef_SendInfo", "Send supplementary information", "Msg_Supplementary"),
                    ("end", "XRef_End2", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
        dict(nodes=[("msgstart", "XRef_OutcomeStart", "Referral outcome notification", "Msg_ReferralOutcome"),
                    ("task", "XRef_Record", "Record outcome and close loop", None),
                    ("end", "XRef_End3", "", None)],
             flows=[(0, 1), (1, 2)]),
    ],
    "Patient": [
        dict(nodes=[("start", "XPat_Start1", "", None),
                    ("task", "XPat_Attend", "Attend appointment / treatment cycle", None),
                    ("send", "XPat_SendVisit", "Confirm attendance", "Msg_ClinicVisitCompleted"),
                    ("end", "XPat_End1", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
        dict(nodes=[("start", "XPat_Start2", "", None),
                    ("task", "XPat_Decide", "Change or cancel appointment", None),
                    ("gw", "XPat_GW", "Change?", None),
                    ("send", "XPat_SendChange", "Request change", "Msg_AppointmentChange"),
                    ("send", "XPat_SendCancel", "Report cancellation", "Msg_Cancellation"),
                    ("end", "XPat_End2", "", None)],
             flows=[(0, 1), (1, 2), (2, 3), (2, 4), (3, 5), (4, 5)]),
        dict(nodes=[("start", "XPat_Start3", "", None),
                    ("task", "XPat_Enquire", "Seek clinical advice / ask question", None),
                    ("send", "XPat_SendEnquiry", "Raise enquiry", "Msg_Enquiry"),
                    ("end", "XPat_End3", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
    ],
    "Scheduling": [
        dict(nodes=[("msgstart", "XSched_Start", "Slot search request", "Msg_SlotSearch"),
                    ("task", "XSched_Search", "Search available slots", None),
                    ("send", "XSched_SendResult", "Return proposed slot", "Msg_SchedulingResult"),
                    ("end", "XSched_End", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
    ],
    "Correspondence": [
        dict(nodes=[("msgstart", "XCorr_ApptStart", "Appointment letter dispatch", "Msg_AppointmentLetter"),
                    ("msgstart", "XCorr_LetterStart", "Letter dispatch", "Msg_LetterDispatch"),
                    ("msgstart", "XCorr_PayStart", "Payment issue notice", "Msg_PaymentIssueNotice"),
                    ("task", "XCorr_Prepare", "Print and despatch correspondence", None),
                    ("end", "XCorr_End", "", None)],
             flows=[(0, 3), (1, 3), (2, 3), (3, 4)]),
    ],
    "PSP": [
        dict(nodes=[("msgstart", "XPSP_PayStart", "Secure payment request", "Msg_PaymentRequest"),
                    ("task", "XPSP_Process", "Process secure payment", None),
                    ("send", "XPSP_SendStatus", "Return payment status", "Msg_PaymentStatus"),
                    ("end", "XPSP_End1", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
        dict(nodes=[("msgstart", "XPSP_RefundStart", "Refund request", "Msg_RefundRequest"),
                    ("task", "XPSP_Refund", "Execute refund", None),
                    ("send", "XPSP_SendRefund", "Return refund result", "Msg_RefundResult"),
                    ("end", "XPSP_End2", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
    ],
    "Funder": [
        dict(nodes=[("msgstart", "XFund_Start", "Funding approval request", "Msg_FundingApprovalRequest"),
                    ("task", "XFund_Assess", "Assess funding application", None),
                    ("send", "XFund_Send", "Return approval decision", "Msg_FundingApproval"),
                    ("end", "XFund_End", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
    ],
    "ExtTreat": [
        dict(nodes=[("msgstart", "XExt_AvailStart", "External availability request", "Msg_ExternalAvailability"),
                    ("task", "XExt_Check", "Check service availability", None),
                    ("gw", "XExt_GW", "Available?", None),
                    ("send", "XExt_SendAvail", "Confirm availability", "Msg_ExternalAvailabilityResult"),
                    ("task", "XExt_Queue", "Queue request and monitor", None),
                    ("send", "XExt_SendReleased", "Notify slot released", "Msg_ExternalSlotReleased"),
                    ("end", "XExt_End1", "", None)],
             flows=[(0, 1), (1, 2), (2, 3), (2, 4), (4, 5), (3, 6), (5, 6)]),
        dict(nodes=[("msgstart", "XExt_LabStart", "Blood test request", "Msg_LabRequest"),
                    ("task", "XExt_Test", "Perform blood test", None),
                    ("send", "XExt_SendLab", "Return lab result", "Msg_LabResult"),
                    ("end", "XExt_End2", "", None)],
             flows=[(0, 1), (1, 2), (2, 3)]),
    ],
}

for _pool, _chains in EXT_PROC.items():
    _sc = "XP_" + _pool
    for _ch in _chains:
        for (_kind, _nid, _name, _msg) in _ch["nodes"]:
            N(_nid, _sc, EXT_KIND[_kind], _name, msg=_msg)
        for (_a, _b) in _ch["flows"]:
            F(_sc, _ch["nodes"][_a][1], _ch["nodes"][_b][1])

# message-flow maps: external responders -> hospital, and hospital -> external receivers
EXT_SEND, EXT_RECV = {}, {}
for _nid, _n in NODES.items():
    if not _n["scope"].startswith("XP_"):
        continue
    if _n["kind"] == "throwEvent" and _n["msg"]:
        EXT_SEND[_n["msg"]] = _nid
    if _n["kind"] == "startEvent" and _n["msg"]:
        EXT_RECV[_n["msg"]] = _nid

# hospital-side message flows (wired to concrete external responder nodes)
for _nid, _n in list(NODES.items()):
    if _n["scope"].startswith("XP_"):
        continue
    if _n["kind"] == "catchEvent" and _n["msg"]:
        _pool = MSG[_n["msg"]][1]
        if _pool:
            MF(EXT_SEND.get(_n["msg"], "Participant_" + _pool), _nid, _n["msg"])
    elif _n["kind"] == "startEvent" and _n["msg"] and _n.get("isInt") is not None:
        _pool = MSG[_n["msg"]][1]
        if _pool:
            MF(EXT_SEND.get(_n["msg"], "Participant_" + _pool), _nid, _n["msg"])
    elif _n["kind"] == "sendTask" and _n["pool"]:
        MF(_nid, EXT_RECV.get(_n["msg"], "Participant_" + _n["pool"]), _n["msg"])

SCOPE_NAME = {
    "SP03B": "P03B Appointment changes (event-driven)",
    "SP09": "P09 Manage treatment changes",
    "SP10": "P10 Cancellation, refusal and no-shows",
    "SP11": "P11 Refunds and fund transfers",
    "SP12": "P12 Patient enquiries",
    "SP13": "P13 Clinic letters",
    "SP14": "P14 Follow-up appointments",
    "SP15": "P15 Monitoring and reports",
    "SP16": "P16 Access, audit and downtime",
}
EMBEDDED = ["SP01", "SP02", "SP03", "SP04", "SP05", "SP06", "SP07", "SP08"]
EVENTS = ["SP03B", "SP09", "SP10", "SP11", "SP12", "SP13", "SP14", "SP15", "SP16"]
ALL_SCOPES = EMBEDDED + EVENTS

# ============================================================================
# LAYOUT
# ============================================================================
TW, TH, EV, GWS = 150, 80, 36, 50          # task / event / gateway sizes
DX, ROWN = 200, 110                         # column pitch / row pitch
SUBW, SUBH = 210, 100                        # collapsed subprocess box at top level
POS = {}                                     # node id -> (x, y, w, h)


def scope_nodes(sc):
    return [n for n in NODES.values() if n["scope"] == sc]


def scope_flows(sc):
    return [f for f in FLOWS if f["scope"] == sc]


def topo_layers(sc):
    """longest-path-from-source layering; cycle edges contribute 0 (deterministic)"""
    nodes = {n["id"] for n in scope_nodes(sc)}
    preds = {}
    for f in scope_flows(sc):
        if f["src"] in nodes and f["tgt"] in nodes:
            preds.setdefault(f["tgt"], []).append(f["src"])
    layer = {}
    instack = set()

    def resolve(n):
        if n in layer:
            return layer[n]
        if n in instack:
            return 0  # cycle edge contributes 0
        instack.add(n)
        best = 0
        for p in preds.get(n, []):
            best = max(best, resolve(p) + 1)
        instack.discard(n)
        layer[n] = best
        return best

    for n in nodes:
        resolve(n)
    return layer


SIZE_OVERRIDE = {"SP13_PrepareScope": (1000, 250)}


def size_of(n):
    if n["id"] in SIZE_OVERRIDE and n["kind"] != "subProcess":
        return SIZE_OVERRIDE[n["id"]]
    k = n["kind"]
    if k in ("startEvent", "endEvent", "catchEvent", "boundaryEvent", "throwEvent"):
        return EV, EV
    if k.endswith("Gateway"):
        return GWS, GWS
    if n["scope"].startswith("XP_") and k == "task":
        return 120, 60
    return TW, TH


def layout_scope(sc, ox, oy):
    """lay out one scope's nodes with optional lane bands; returns (w, h, lane_shapes)"""
    nodes = scope_nodes(sc)
    lanes = []
    for n in nodes:
        if n["lane"] and n["lane"] not in lanes:
            lanes.append(n["lane"])
    if not lanes:
        lanes = [""]  # single pseudo band for lane-less scopes
    lane_nodes = {ln: [] for ln in lanes}
    for n in nodes:
        ln = n["lane"] if (n["lane"] and n["lane"] in lane_nodes) else lanes[0]
        lane_nodes[ln].append(n)
    layer = topo_layers(sc)
    order_key = {nid: i for i, nid in enumerate(NODES)}
    y = oy + 30
    lane_shapes = []
    max_w = 0
    for ln in lanes:
        ns = sorted(lane_nodes[ln], key=lambda n: (layer[n["id"]], order_key[n["id"]]))
        col_rows, row_top, row_h = {}, {}, {}
        for n in ns:
            L = layer[n["id"]]
            r = col_rows.get(L, 0)
            col_rows[L] = r + 1
            if r not in row_top:
                row_top[r] = y + 20 + sum(row_h.get(k, 0) + 12 for k in range(r))
            w, h = size_of(n)
            x = ox + 60 + L * DX
            POS[n["id"]] = (x, row_top[r], w, h)
            max_w = max(max_w, x + w)
            row_h[r] = max(row_h.get(r, 0), h)
        band_h = 20 + sum(v + 12 for v in row_h.values())
        lane_shapes.append((ln, ox, y, max(max_w + 60 - ox, 300), band_h))
        y += band_h + 14
    return (max_w + 120 - ox), (y - oy), lane_shapes


CONTENT_SCOPE = {"SP13_PrepareScope": "SP13_PS"}  # container node id -> content scope id


def edge_points(fid):
    """orthogonal-ish waypoints for a sequence flow"""
    s, t = None, None
    for f in FLOWS:
        if f["id"] == fid:
            s, t = POS[f["src"]], POS[f["tgt"]]
            break
    sx, sy, sw, sh = s
    tx, ty, tw_, th_ = t
    if tx >= sx + sw + 8:  # forward
        a, b = (sx + sw, sy + sh // 2), (tx, ty + th_ // 2)
        if abs(a[1] - b[1]) <= 6:
            return [a, b]
        mx = (a[0] + b[0]) // 2
        return [a, (mx, a[1]), (mx, b[1]), b]
    # backward: route below both
    yy = max(sy + sh, ty + th_) + 55
    return [(sx + sw // 2, sy + sh), (sx + sw // 2, yy), (tx + tw_ // 2, yy), (tx + tw_ // 2, ty)]


# ---------------- top level positions ----------------
TOP_Y_MAIN = 70
x = 80
chain = [
    ("Top_Start", "event"), ("Top_Catch_Referral", "event"),
    ("SP01", "sub"), ("SP02", "sub"), ("GW_SP02_Exit", "gw"),
    ("SP03", "sub"), ("GW_SP03_Exit", "gw"), ("SP04", "sub"), ("GW_SP04_Exit", "gw"),
    ("SP05", "sub"), ("SP06", "sub"), ("GW_SP06_Exit", "gw"), ("SP07", "sub"), ("SP08", "sub"),
    ("Top_WaitClosure", "event"), ("Top_NotifyClosure", "task"), ("Top_End_Closed", "event"),
]
for nid, kind in chain:
    if kind == "sub":
        POS[nid] = (x, TOP_Y_MAIN, SUBW, SUBH); x += SUBW + 70
    elif kind == "gw":
        POS[nid] = (x, TOP_Y_MAIN + (SUBH - GWS) // 2, GWS, GWS); x += GWS + 45
    elif kind == "task":
        POS[nid] = (x, TOP_Y_MAIN + (SUBH - TH) // 2, TW, TH); x += TW + 55
    else:
        POS[nid] = (x, TOP_Y_MAIN + (SUBH - EV) // 2, EV, EV); x += EV + 45
HOSP_W = x + 60
POS["Top_End_ReferralClosed"] = (POS["GW_SP02_Exit"][0] - 20, TOP_Y_MAIN + SUBH + 120, EV, EV)
TOP_Y_EVT = TOP_Y_MAIN + SUBH + 220
x = 80
for sc in EVENTS:
    POS[sc] = (x, TOP_Y_EVT, SUBW, SUBH); x += SUBW + 70
HOSP_H = TOP_Y_EVT + SUBH + 60

# ---------------- external pools with mock response processes ----------------
POOL_Y = HOSP_H + 150
POOL_W = 760
EXT_DX = 140


def layout_ext(pool, px, py):
    """lay out one pool's mock chains; each chain gets its own row band"""
    sc = "XP_" + pool
    y = py + 24
    for ch in EXT_PROC[pool]:
        ids = [nd[1] for nd in ch["nodes"]]
        layer = {i: 0 for i in ids}
        for _ in range(len(ids) + 1):
            for (a, b) in ch["flows"]:
                na, nb = ids[a], ids[b]
                if layer[nb] < layer[na] + 1:
                    layer[nb] = layer[na] + 1
        col_rows, row_top, row_h = {}, {}, {}
        for nd in ch["nodes"]:
            n = NODES[nd[1]]
            L = layer[n["id"]]
            r = col_rows.get(L, 0)
            col_rows[L] = r + 1
            if r not in row_top:
                row_top[r] = y + sum(row_h.get(k, 0) + 14 for k in range(r))
            w, h = size_of(n)
            POS[n["id"]] = (px + 46 + L * EXT_DX, row_top[r], w, h)
            row_h[r] = max(row_h.get(r, 0), h)
        y += sum(v + 14 for v in row_h.values()) + 26
    return y - py + 8


pool_h_needed = {}
_px = 40
for _pid in POOLS:
    pool_h_needed[_pid] = layout_ext(_pid, _px, POOL_Y)
    _px += POOL_W + 70
POOL_H = max(pool_h_needed.values())
pool_x = {}
_px = 40
for _pid in POOLS:
    pool_x[_pid] = (_px, POOL_Y, POOL_W, POOL_H)
    _px += POOL_W + 70
CANVAS_W = max(_px, HOSP_W + 40)

# ---------------- shelves (subprocess internals) ----------------
SHELF_Y = POOL_Y + POOL_H + 130
shelf_map = {}
sy = SHELF_Y
for sc in ALL_SCOPES:
    w, h, lanes = layout_scope(sc, 80, sy)
    if sc == "SP13":
        # letter-preparation scope moved below its row, then nested content filled in
        sb = POS["SP13_PrepareScope"]
        bx, by = sb[0], sy + h + 70
        POS["SP13_PrepareScope"] = (bx, by, 1000, 250)
        POS["SP13_Timer7d"] = (bx - 15, by + 250 - 52, EV, EV)
        w2, h2, lanes2 = layout_scope("SP13_PS", bx + 40, by + 40)
        shelf_map["SP13_PS"] = (bx, by, w2, h2, lanes2)
        h = max(h, by + 250 + 60 - sy)
    shelf_map[sc] = (80, sy, w, h, lanes)
    sy += h + 130
CANVAS_H = sy

# ============================================================================
# XML EMISSION
# ============================================================================
def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


L = []
A = L.append
A('<?xml version="1.0" encoding="UTF-8"?>')
A('<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"')
A('                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"')
A('                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"')
A('                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"')
A('                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"')
A('                  xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"')
A('                  id="Definitions_HPAS" targetNamespace="http://bpmn.io/schema/bpmn"')
A('                  exporter="hpas build_framework.py" exporterVersion="1">')
for mid, (name, _pool) in MSG.items():
    A('  <bpmn:message id="%s" name="%s">' % (mid, esc(name)))
    A('    <bpmn:extensionElements>')
    A('      <zeebe:subscription correlationKey="= referralId" />')
    A('    </bpmn:extensionElements>')
    A('  </bpmn:message>')
A('  <bpmn:collaboration id="Collaboration_HPAS" name="%s">' % esc(
    "Hospital Patient Referral, Treatment and Administration System (HPAS)"))
A('    <bpmn:participant id="Participant_Hospital" name="%s" processRef="Process_HPAS" />' % esc(HOSPITAL))
for pid, pname in POOLS.items():
    A('    <bpmn:participant id="Participant_%s" name="%s" processRef="Proc_%s" />' % (pid, esc(pname), pid))
for mf in MFS:
    A('    <bpmn:messageFlow id="%s" sourceRef="%s" targetRef="%s" messageRef="%s" />' %
      (mf["id"], mf["src"], mf["tgt"], mf["msg"]))
A('  </bpmn:collaboration>')


def node_xml(n, ind):
    p = " " * ind
    nid, k, name = n["id"], n["kind"], n["name"]
    extra = ""
    if n["isInt"] is not None:
        extra += ' isInterrupting="%s"' % ("true" if n["isInt"] else "false")
    if n["att"]:
        extra += ' attachedToRef="%s" cancelActivity="false"' % n["att"]
    ins = [f["id"] for f in FLOWS if f["scope"] == CURRENT and f["tgt"] == nid]
    outs = [f["id"] for f in FLOWS if f["scope"] == CURRENT and f["src"] == nid]
    tag = {"startEvent": "startEvent", "endEvent": "endEvent", "catchEvent": "intermediateCatchEvent",
           "boundaryEvent": "boundaryEvent", "exclusiveGateway": "exclusiveGateway",
           "eventBasedGateway": "eventBasedGateway", "userTask": "userTask",
           "serviceTask": "serviceTask", "sendTask": "sendTask",
           "task": "task", "throwEvent": "intermediateThrowEvent"}[k]
    A('%s<bpmn:%s id="%s" name="%s"%s>' % (p, tag, nid, esc(name), extra))
    if n["job"]:
        A('%s  <bpmn:extensionElements>' % p)
        A('%s    <zeebe:taskDefinition type="%s" retries="3" />' % (p, n["job"]))
        A('%s  </bpmn:extensionElements>' % p)
    elif n["cg"]:
        A('%s  <bpmn:extensionElements>' % p)
        A('%s    <zeebe:assignmentDefinition candidateGroups="%s" />' % (p, n["cg"]))
        A('%s  </bpmn:extensionElements>' % p)
    for i in ins:
        A('%s  <bpmn:incoming>%s</bpmn:incoming>' % (p, i))
    for o in outs:
        A('%s  <bpmn:outgoing>%s</bpmn:outgoing>' % (p, o))
    if n["msg"] and n["kind"] in ("startEvent", "catchEvent", "boundaryEvent", "throwEvent"):
        A('%s  <bpmn:messageEventDefinition messageRef="%s" />' % (p, n["msg"]))
    if n["timer"]:
        A('%s  <bpmn:timerEventDefinition>' % p)
        A('%s    <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">%s</bpmn:timeDuration>' % (p, n["timer"]))
        A('%s  </bpmn:timerEventDefinition>' % p)
    A('%s</bpmn:%s>' % (p, tag))


def scope_xml(sc, ind, is_event, node_id=None):
    global CURRENT
    p = " " * ind
    nid = node_id or sc
    name = NODES[nid]["name"] if nid in NODES else SCOPE_NAME.get(sc, sc)
    trig = ' triggeredByEvent="true"' if is_event else ""
    A('%s<bpmn:subProcess id="%s" name="%s"%s>' % (p, nid, esc(name), trig))
    nodes = scope_nodes(sc)
    lanes = []
    for n in nodes:
        if n["lane"] and n["lane"] not in lanes:
            lanes.append(n["lane"])
    if not is_event and nid in NODES:
        parent = NODES[nid]["scope"]
        for f in FLOWS:
            if f["scope"] == parent and f["tgt"] == nid:
                A('%s  <bpmn:incoming>%s</bpmn:incoming>' % (p, f["id"]))
        for f in FLOWS:
            if f["scope"] == parent and f["src"] == nid:
                A('%s  <bpmn:outgoing>%s</bpmn:outgoing>' % (p, f["id"]))
    if lanes:
        A('%s  <bpmn:laneSet id="LaneSet_%s">' % (p, sc))
        for i, ln in enumerate(lanes):
            A('%s    <bpmn:lane id="Lane_%s_%d" name="%s">' % (p, sc, i, esc(ln)))
            for n in nodes:
                if n["lane"] == ln or (not n["lane"] and i == 0):
                    A('%s      <bpmn:flowNodeRef>%s</bpmn:flowNodeRef>' % (p, n["id"]))
            A('%s    </bpmn:lane>' % p)
        A('%s  </bpmn:laneSet>' % p)
    saved = CURRENT
    CURRENT = sc
    for n in nodes:
        if n["id"] == sc:
            continue
        if n["kind"] == "subProcess":
            scope_xml(CONTENT_SCOPE.get(n["id"], n["id"]), ind + 2, False, node_id=n["id"])
        else:
            node_xml(n, ind + 2)
    for f in scope_flows(sc):
        cond = ""
        if f["cond"]:
            c = f["cond"]
            if not c.startswith("="):
                c = "= " + c
            cond = '<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">%s</bpmn:conditionExpression>' % esc(c)
        nm = ' name="%s"' % esc(f["name"]) if f["name"] else ""
        A('%s  <bpmn:sequenceFlow id="%s"%s sourceRef="%s" targetRef="%s">%s</bpmn:sequenceFlow>'
          % (p, f["id"], nm, f["src"], f["tgt"], cond))
    CURRENT = saved
    A('%s</bpmn:subProcess>' % p)


CURRENT = "TOP"
A('  <bpmn:process id="Process_HPAS" name="%s" isExecutable="true">' % esc(HOSPITAL))
A('    <bpmn:laneSet id="LaneSet_TOP">')
A('      <bpmn:lane id="Lane_Coordination" name="Patient Pathway Coordination">')
for n in scope_nodes("TOP"):
    A('        <bpmn:flowNodeRef>%s</bpmn:flowNodeRef>' % n["id"])
A('      </bpmn:lane>')
A('    </bpmn:laneSet>')
CURRENT = "TOP"
for n in scope_nodes("TOP"):
    if n["kind"] == "subProcess":
        continue
    node_xml(n, 4)
for sc in EMBEDDED:
    scope_xml(sc, 4, False)
for sc in EVENTS:
    scope_xml(sc, 4, True)
for f in scope_flows("TOP"):
    cond = ""
    if f["cond"]:
        c = f["cond"]
        if not c.startswith("="):
            c = "= " + c
        cond = '<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">%s</bpmn:conditionExpression>' % esc(c)
    nm = ' name="%s"' % esc(f["name"]) if f["name"] else ""
    A('    <bpmn:sequenceFlow id="%s"%s sourceRef="%s" targetRef="%s">%s</bpmn:sequenceFlow>'
      % (f["id"], nm, f["src"], f["tgt"], cond))
A('  </bpmn:process>')
for pid, pname in POOLS.items():
    A('  <bpmn:process id="Proc_%s" name="%s">' % (pid, esc(pname)))
    CURRENT = "XP_" + pid
    for n in scope_nodes("XP_" + pid):
        node_xml(n, 4)
    for f in scope_flows("XP_" + pid):
        A('    <bpmn:sequenceFlow id="%s" sourceRef="%s" targetRef="%s" />' % (f["id"], f["src"], f["tgt"]))
    A('  </bpmn:process>')

# ---------------- DI ----------------
A('  <bpmndi:BPMNDiagram id="BPMNDiagram_HPAS">')
A('    <bpmndi:BPMNPlane id="BPMNPlane_HPAS" bpmnElement="Collaboration_HPAS">')


def shape(nid, x, y, w, h, expanded=None, horizontal=None):
    ex = ' isExpanded="%s"' % expanded if expanded is not None else ""
    ho = ' isHorizontal="true"' if horizontal else ""
    A('      <bpmndi:BPMNShape id="di_%s" bpmnElement="%s"%s%s>' % (nid, nid, ex, ho))
    A('        <dc:Bounds x="%d" y="%d" width="%d" height="%d" />' % (x, y, w, h))
    A('      </bpmndi:BPMNShape>')


A('      <bpmndi:BPMNShape id="di_Participant_Hospital" bpmnElement="Participant_Hospital" isHorizontal="true">')
A('        <dc:Bounds x="0" y="0" width="%d" height="%d" />' % (HOSP_W, HOSP_H))
A('      </bpmndi:BPMNShape>')
for pid, (px, py, pw, ph) in pool_x.items():
    A('      <bpmndi:BPMNShape id="di_Participant_%s" bpmnElement="Participant_%s" isHorizontal="true">' % (pid, pid))
    A('        <dc:Bounds x="%d" y="%d" width="%d" height="%d" />' % (px, py, pw, ph))
    A('      </bpmndi:BPMNShape>')
# top lane
A('      <bpmndi:BPMNShape id="di_Lane_Coordination" bpmnElement="Lane_Coordination" isHorizontal="true">')
A('        <dc:Bounds x="30" y="30" width="%d" height="%d" />' % (HOSP_W - 30, HOSP_H - 30))
A('      </bpmndi:BPMNShape>')
# top-level nodes
for n in scope_nodes("TOP"):
    if n["kind"] == "subProcess":
        continue
    x0, y0, w0, h0 = POS[n["id"]]
    shape(n["id"], x0, y0, w0, h0)
# subprocess shapes (collapsed, at top)
for sc in EMBEDDED + EVENTS:
    x0, y0, w0, h0 = POS[sc]
    shape(sc, x0, y0, w0, h0, expanded="false")
# shelf lane shapes (skip empty pseudo-lanes of event subprocesses)
for sc, (sx, sy_, sw, sh_, lanes) in shelf_map.items():
    for i, (ln, lx, ly, lw, lh) in enumerate(lanes):
        if not ln:
            continue
        shape("Lane_%s_%d" % (sc, i), lx, ly, lw, lh, horizontal=True)
# all inner nodes
for sc in ALL_SCOPES + ["SP13_PS"]:
    for n in scope_nodes(sc):
        if n["id"] == sc:
            continue
        x0, y0, w0, h0 = POS[n["id"]]
        if n["id"] == "SP13_PrepareScope":
            shape(n["id"], x0, y0, w0, h0, expanded="true")
        else:
            shape(n["id"], x0, y0, w0, h0)
# external mock process nodes
for _pid in POOLS:
    for n in scope_nodes("XP_" + _pid):
        x0, y0, w0, h0 = POS[n["id"]]
        shape(n["id"], x0, y0, w0, h0)
# sequence flow edges
for f in FLOWS:
    pts = edge_points(f["id"])
    A('      <bpmndi:BPMNEdge id="di_%s" bpmnElement="%s">' % (f["id"], f["id"]))
    for (px, py) in pts:
        A('        <di:waypoint x="%d" y="%d" />' % (px, py))
    A('      </bpmndi:BPMNEdge>')
# message flow edges (hospital <-> external responder nodes; jog lanes above pools)
def mf_anchor(nref):
    if nref.startswith("Participant_"):
        pid = nref.split("Participant_")[1]
        return pool_x[pid]
    n = NODES[nref]
    if n["scope"] != "TOP" and not n["scope"].startswith("XP_"):
        return POS[n["scope"]]  # collapsed subprocess box
    return POS[nref]


for _i, mf in enumerate(MFS):
    a = mf_anchor(mf["src"])
    b = mf_anchor(mf["tgt"])
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    jog = POOL_Y - 78 + (_i % 7) * 9
    if by >= ay + ah + 20:  # hospital -> external (target below)
        pts = [(ax + aw // 2, ay + ah), (ax + aw // 2, jog), (bx + bw // 2, jog), (bx + bw // 2, by)]
    elif ay >= by + bh + 20:  # external -> hospital (target above)
        pts = [(ax + aw // 2, ay), (ax + aw // 2, jog), (bx + bw // 2, jog), (bx + bw // 2, by + bh)]
    else:
        pts = [(ax + aw, ay + ah // 2), (bx, by + bh // 2)]
    A('      <bpmndi:BPMNEdge id="di_%s" bpmnElement="%s">' % (mf["id"], mf["id"]))
    for (px_, py_) in pts:
        A('        <di:waypoint x="%d" y="%d" />' % (px_, py_))
    A('      </bpmndi:BPMNEdge>')
A('    </bpmndi:BPMNPlane>')
A('  </bpmndi:BPMNDiagram>')
A('</bpmn:definitions>')

with open(os.path.abspath(OUT), "w", encoding="utf-8") as fh:
    fh.write("\n".join(L) + "\n")

print("written:", os.path.abspath(OUT))
print("nodes:", len(NODES), "flows:", len(FLOWS), "messageFlows:", len(MFS), "messages:", len(MSG))
print("canvas:", CANVAS_W, "x", CANVAS_H)

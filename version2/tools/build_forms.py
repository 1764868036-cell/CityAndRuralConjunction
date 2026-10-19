"""Generate one deployment-bound Camunda 8 form per redesigned user task."""

import json
from pathlib import Path
from lxml import etree as ET

ROOT = Path(__file__).resolve().parent.parent
BPMN = ROOT / "hospital-patient-pathway.bpmn"
OUT = ROOT / "forms"
OUT.mkdir(exist_ok=True)
N = {"b": "http://www.omg.org/spec/BPMN/20100524/MODEL"}


def text(key, label, required=False, multiline=False):
    return {"key": key, "label": label, "type": "textarea" if multiline else "textfield",
            "validate": {"required": required, "maxLength": 2000 if multiline else 160}}


def choice(key, label, values, required=True):
    return {"key": key, "label": label, "type": "select",
            "values": [{"label": a, "value": b} for a, b in values],
            "validate": {"required": required}}


def flag(key, label):
    return {"key": key, "label": label, "type": "checkbox", "defaultValue": False}


def number(key, label, required=False):
    return {"key": key, "label": label, "type": "number",
            "validate": {"required": required, "min": 0}}


FIELDS = {
    "SP01_CheckDocuments": [flag("documentsComplete", "All required referral documents received"),
                            text("missingDocuments", "Missing documents and request details", multiline=True),
                            text("patientId", "Verified patient ID", True)],
    "SP02_ClinicalReview": [choice("reviewDecision", "Authorised consultant decision",
                                   [(x.title(), x) for x in ("accept", "reject", "information", "redirect")]),
                            text("reviewReason", "Clinical reason for decision", True, True),
                            text("decisionMaker", "Consultant identity", True),
                            flag("urgentReferral", "Urgent referral")],
    "SP03_NoSlotReview": [text("slotExceptionReason", "Reason no slot meets clinical timeframe", True, True),
                          text("pathwayEscalation", "Pathway team decision", True, True)],
    "SP03_PhoneContact": [choice("contactOutcome", "Telephone contact outcome",
                                 [("Reached", "reached"), ("No answer", "no-answer"),
                                  ("Wrong number", "wrong-number"), ("Alternative requested", "alternative")]),
                          number("phoneAttempts", "Number of attempts including this call", True),
                          text("contactNotes", "Contact details / patient preferences", multiline=True)],
    "SP03_EscalateContact": [text("contactEscalation", "Action agreed after unsuccessful contact", True, True)],
    "SP04_Assess": [text("diagnosisSummary", "Diagnosis and clinical findings", True, True),
                    text("treatmentOptions", "Options and risks explained", True, True),
                    flag("consentGiven", "Patient agrees to proceed with treatment")],
    "SP04_RecordConsent": [text("consentReference", "Signed consent record reference", True),
                           text("consentDiscussion", "Consent discussion", True, True)],
    "SP04_CreateRequest": [text("treatmentPlanId", "Treatment plan ID", True),
                           text("treatmentDescription", "Treatment and resources", True, True),
                           text("requiredStartDate", "Required start date (YYYY-MM-DD)", True),
                           number("cycleCount", "Number of cycles", True),
                           choice("resourceType", "Booking resource", [("Hospital", "internal"), ("External", "external")]),
                           text("authorisingClinician", "Authorising clinician", True)],
    "SP06_RecordApproval": [text("fundingOrganisation", "Funding organisation", True),
                            text("authorisationReference", "Approval reference", True),
                            number("approvedAmount", "Approved amount", True),
                            text("approvalLimits", "Limitations of approval", multiline=True)],
    "SP07_RecordOverride": [text("urgentOverrideReason", "Clinical risk requiring immediate treatment", True, True),
                            text("overrideClinician", "Authorising clinician", True)],
    "SP07_DecideRetry": [choice("retryDecision", "Finance action after failed or incomplete payment",
                                [("New payment attempt", "retry"),
                                 ("Request clinician-authorised urgent exception", "urgent-override")]),
                           text("paymentResolutionReason", "Reason and patient notification", True, True)],
    "SP07_Investigate": [choice("investigationOutcome", "PSP investigation outcome",
                                [("Payment taken", "taken"), ("Payment not taken", "not-taken")]),
                           text("transactionReference", "PSP transaction reference", True),
                           text("investigationNotes", "Evidence and reconciliation notes", True, True)],
    "SP08_Review": [choice("fitnessDecision", "Clinical fitness decision",
                            [("Fit to continue", "fit"), ("Delay", "delay"), ("Change plan", "change")]),
                    flag("moreCycles", "Further treatment cycles planned"),
                    text("bloodTestReference", "Blood test / review reference", True),
                    text("clinicalReason", "Clinical reasoning", True, True)],
    "SP08_DelayHandling": [text("delayReason", "Clinical reason for delay", True, True),
                           text("resumptionCriteria", "Review / resumption criteria", True, True)],
    "SP03B_Review": [text("changeReview", "Scheduling conflict and pathway review", True, True)],
    "SP09_Retrospective": [flag("formallyAuthorised", "Urgent postponement formally authorised"),
                           text("retrospectiveReason", "Clinical reason and timing", True, True),
                           text("authorisingClinician", "Authorising clinician", True)],
    "SP09_FinanceReview": [flag("financialImpact", "Funding or charge affected"),
                           flag("refundRequired", "Refer for refund decision"),
                           text("financialChangeNotes", "Financial impact and evidence", True, True)],
    "SP10_Record": [choice("attendanceEvent", "Appointment event",
                           [("Cancelled", "cancelled"), ("Declined", "declined"), ("Did not attend", "no-show")]),
                    flag("appointmentPaid", "Appointment already paid"),
                    text("eventEvidence", "Contact attempt and event evidence", True, True)],
    "SP10_Decide": [choice("pathwayResponse", "Authorised clinical pathway response",
                           [("Offer another appointment", "re-offer"), ("Clinical review", "clinical-review"),
                            ("Close and inform referrer", "close")]),
                    text("responseReason", "Clinical reason for decision", True, True)],
    "SP10_ClinicalReview": [text("clinicalReviewOutcome", "Clinical pathway review outcome", True, True)],
    "SP11_Determine": [flag("refundDue", "Refund approved"),
                       choice("refundScope", "Refund scope", [("Full", "full"), ("Partial", "partial"),
                                                               ("Transfer", "transfer"), ("None", "none")]),
                       number("refundAmount", "Approved refund amount"),
                       text("refundReason", "Financial decision and reason", True, True),
                       text("financeApprover", "Authorised Finance approver", True)],
    "SP12_Record": [choice("enquiryClass", "Enquiry classification",
                           [("Simple administration", "admin-simple"), ("Transfer administration", "admin-transfer"),
                            ("Financial", "financial"), ("Clinical", "clinical")]),
                    text("enquiryDetails", "Enquiry and requested response", True, True),
                    text("enquiryPriority", "Initial priority / concern noted", True)],
    "SP12_AssessPriority": [flag("urgentConcern", "CNS assesses urgent clinical concern"),
                            text("urgencyReason", "Clinical priority assessment", True, True)],
    "SP12_Answer": [text("enquiryResponse", "Administrative response", True, True)],
    "SP12_Transfer": [text("assignedTeam", "Responsible administrative team", True),
                      text("transferNotes", "Handover notes", True, True)],
    "SP12_AnswerFin": [flag("infoAuthorised", "Response uses authorised information only"),
                       text("enquiryResponse", "Financial response", True, True)],
    "SP12_ReferFinance": [text("enquiryResponse", "Finance Team response", True, True)],
    "SP12_ReferCNS": [text("enquiryResponse", "CNS clinical response", True, True)],
    "SP13_Draft": [text("clinicLetterDraft", "Clinical findings, decisions and follow-up", True, True),
                   text("letterRecipients", "Intended recipients", True)],
    "SP13_Approve": [flag("letterApproved", "Consultant approves clinical content"),
                     text("letterApprovalNotes", "Approval notes", multiline=True)],
    "SP13_Reminder": [text("reminderNotes", "Weekly reminder and Consultant response", True, True)],
    "SP13_AdminProcess": [choice("adminCheck", "Administrative check",
                                 [("Ready to distribute", "ok"), ("Clinical error: return to Consultant", "clinical-error")]),
                            flag("followUpInstructed", "Follow-up requested in letter"),
                            text("confirmedRecipients", "Confirmed recipients", True),
                            text("deliveryChannel", "Approved distribution channel", True)],
    "SP14_Highlight": [text("followUpConflict", "Unavailable within requested period", True, True),
                       text("pathwayDecision", "Pathway team response", True, True)],
    "SP15_EscalateManager": [text("managerContact", "Manager contact with Consultant", True, True)],
    "SP15_EscalateHigher": [text("higherEscalation", "Higher management escalation", True, True)],
    "SP16_VerifyAuth": [text("staffIdentity", "Verified staff identity", True),
                        text("requestedAccess", "Requested role / access", True),
                        flag("accessAuthorised", "Access approved")],
    "SP16_RecordDowntime": [text("incidentReference", "Downtime incident reference", True),
                            text("offlineActions", "Actions performed during unavailability", True, True)],
}


tree = ET.parse(str(BPMN))
tasks = tree.xpath('//b:process[@id="Process_HPAS"]//b:userTask', namespaces=N)
task_ids = {t.get("id") for t in tasks}
assert task_ids == set(FIELDS), f"form spec mismatch: missing {task_ids - set(FIELDS)}, excess {set(FIELDS) - task_ids}"
for task in tasks:
    tid = task.get("id")
    components = [{"id": f"heading_{tid}", "type": "text", "text": f"# {task.get('name')}"}]
    for i, field in enumerate(FIELDS[tid]):
        item = dict(field)
        item["id"] = f"{tid}_field_{i}"
        item["layout"] = {"row": f"{tid}_row_{i}", "columns": None}
        components.append(item)
    components.append({"id": f"audit_{tid}", "type": "text",
                       "text": "Record the responsible person's identity; the system records the submission time and task audit."})
    components.append({"id": f"actor_{tid}", "type": "textfield", "key": f"{tid}_actor",
                       "label": "Responsible staff ID", "validate": {"required": True, "maxLength": 80},
                       "layout": {"row": f"{tid}_actor", "columns": None}})
    data = {"type": "default", "id": f"hpas_{tid.lower()}",
            "executionPlatform": "Camunda Cloud", "executionPlatformVersion": "8.9.0",
            "schemaVersion": 19, "components": components}
    (OUT / f"{data['id']}.form").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Generated {len(tasks)} forms in {OUT}")

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""One-off patch: add lane assignments to event-subprocess nodes in build_framework.py"""
import io
import sys

P = "build_framework.py"
src = io.open(P, encoding="utf-8").read()

LANE = {
    # SP03B
    '"Search new slot via scheduling service",': "Outpatient Bookings Team",
    '"Apply reschedule without duplicate booking",': "Outpatient Bookings Team",
    '"Notify patient of changed appointment",': "Outpatient Bookings Team",
    '"Pathway team review of change request",': "Outpatient Bookings Team",
    # SP09
    '"Execute immediate postponement (patient safety)",': "Clinical Team",
    '"Record and authorise decision retrospectively",': "Consultants",
    '"Validate formal authorisation and traceability",': "Clinical Team",
    '"Apply treatment modification",': "Clinical Team",
    '"Notify updated treatment plan",': "Clinical Team",
    '"Review financial implications",': "Finance Team",
    # SP10
    '"Record cancellation / refusal / non-attendance event",': "Call Handling Team",
    '"Refer to Finance for retain / transfer / refund",': "Finance Team",
    '"Decide pathway response (clinical responsibility)",': "Consultants",
    '"Request re-booking",': "Call Handling Team",
    '"Review patient pathway",': "Clinical Team",
    '"Inform referring organisation",': "Call Handling Team",
    # SP11
    '"Determine full / partial refund (financial authority)",': "Finance Team",
    # SP12
    '"Record and classify enquiry",': "Call Handling Team",
    '"Answer administrative question directly",': "Call Handling Team",
    '"Transfer to responsible administrative team",': "Call Handling Team",
    '"Answer financial enquiry from authorised information",': "Call Handling Team",
    '"Respond to financial enquiry",': "Finance Team",
    '"Flag urgent clinical concern (type-based auto-classification)",': "Call Handling Team",
    '"Respond to clinical enquiry (CNS team)",': "Clinical Nurse Specialist Team",
    '"Record handling, team assignment, response, resolution",': "Call Handling Team",
    # SP13
    '"Letter preparation (7-day SLA)"': "Consultants",
    '"Issue weekly reminder to Consultant",': "Patient Pathway Coordinators",
    '"Administrative checks, confirm recipients",': "Medical Secretaries",
    '"Arrange distribution via approved channels",': "Medical Secretaries",
    '"Record dates and method of distribution",': "Medical Secretaries",
    '"Send follow-up appointment instruction",': "Consultants",
    # SP13_PS
    '"Prepare clinic letter (clinical content)",': "Consultants",
    '"Approve clinical content",': "Consultants",
    # SP14
    '"Search follow-up slot within requested period",': "Outpatient Bookings Team",
    '"Record follow-up booking",': "Outpatient Bookings Team",
    '"Inform patient via appropriate channel",': "Outpatient Bookings Team",
    '"Highlight case and refer to pathway team",': "Patient Pathway Coordinators",
    # SP15
    '"Scan outstanding items (letters, referrals, contacts, bookings, funding, payments, refunds, enquiries)",': "Patient Pathway Coordinators",
    '"Administrative Manager contacts responsible Consultant",': "Administrative Management Team",
    '"Escalate to higher management team",': "Administrative Management Team",
    '"Generate management reports (volumes, delays, pathway progress)",': "Patient Pathway Coordinators",
    # SP16
    '"Verify staff identity and authorisation",': "Administrative Management Team",
    '"Record activities completed during unavailability",': "Administrative Management Team",
}

for frag, lane in LANE.items():
    n = src.count(frag)
    if n != 1:
        print("ASSERT FAILED (%d occurrences): %s" % (n, frag))
        sys.exit(1)
    if frag.endswith('"'):
        # subProcess call: name at end, no comma
        new = frag + ", lane=\"%s\"" % lane
    else:
        new = frag + " lane=\"%s\"," % lane
    src = src.replace(frag, new, 1)

io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("patched %d lane assignments" % len(LANE))

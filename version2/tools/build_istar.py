"""Build the BPMN-aligned VERSION2 i* strategic model using Python's standard library.

Run from any directory: python version2/tools/build_istar.py
Goals describe required service capabilities, not mandatory steps in one case.
Every BPMN collaboration participant is represented at the strategic boundary.
"""
import collections
import hashlib
import heapq
import json
import math
import pathlib
import random
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
BPMN = ROOT / 'hospital-patient-pathway.bpmn'
OUT = ROOT / 'istar'
OUT.mkdir(exist_ok=True)
M = {'actors': [], 'orphans': [], 'dependencies': [], 'links': [], 'display': {},
     'tool': 'pistar.2.1.0', 'istar': '2.0',
     'diagram': {'width': 4950, 'height': 4880, 'name': 'Hospital VERSION2 - BPMN-Aligned Integrated SD and SR',
     'customProperties': {
         'Source file': 'hospital-patient-pathway.bpmn in version2',
         'Source SHA256': hashlib.sha256(BPMN.read_bytes()).hexdigest(),
         'Scope': 'P01-P08 and event branches P03B, P09-P16; all eight BPMN participants represented; strategic capabilities, not sequence flow.',
         'Reading': 'Depender task -> dependum -> provider task. AND means required capabilities, not all branches executing in one case. HELP means positive contribution, not a guarantee.',
         'Abstraction': 'The strategic actor boundary follows the BPMN collaboration: Patient, Referring organisation, External Scheduling Service, External Correspondence Service, Payment Service Provider, Insurer / Funding Organisation, and External Treatment / Laboratory / Imaging Service are external. Provider capabilities are abstracted from their BPMN participant tasks and message exchanges. Tracking, access, downtime and management escalation remain hospital responsibilities.',
         'External actors': 'Patient; Referring organisation; External Scheduling Service; External Correspondence Service; Payment Service Provider; Insurer / Funding Organisation; External Treatment / Laboratory / Imaging Service.',
         'Business boundary': 'The Hospital Patient Administration System is the only hospital participant. Its internal lanes are represented as hospital actors. The external laboratory, treatment, scheduling, correspondence, funding and payment providers remain outside the hospital boundary. SP05 availability is requested by the HTTP JSON connector; provider work is an intentional strategic abstraction traced to the connector and external service tasks.',
         'Layout': 'One green integrated model; all seven external actors are visible; all dependencies end on specific tasks; editable elements use native piStar types and the SVG is generated from this source.'}}}
COLORS = {'Goal': '#cfe8d3', 'Task': '#e1f0df', 'Resource': '#edf5df', 'Quality': '#bfe1c8'}
A, N, owner = {}, {}, {}

def node(a, key, text, refs, typ='Task', condition=''):
    i = 'n_' + a + '_' + key
    cp = {'Source BPMN IDs': refs, 'Full wording': text}
    if condition: cp['Applicability'] = condition
    n = {'id': i, 'type': 'istar.' + typ, 'text': text, 'x': 0, 'y': 0, 'customProperties': cp}
    A[a]['nodes'].append(n); N[i] = n; owner[i] = 'actor_' + a
    M['display'][i] = {'width': 190, 'height': 62, 'backgroundColor': COLORS[typ]}
    return i

def link(s, t, typ='AndRefinementLink'):
    i = 'link_' + str(len(M['links']))
    M['links'].append({'id': i, 'type': 'istar.' + typ, 'source': s, 'target': t})

def actor(key, title, goal, rows, external=False):
    a = {'id': 'actor_' + key, 'type': 'istar.Actor', 'text': title, 'x': 0, 'y': 0,
         'nodes': [], 'customProperties': {'Full actor name': title, 'Scope': 'External' if external else 'Hospital'}}
    A[key] = a; M['actors'].append(a)
    M['display'][a['id']] = {'collapsed': False, 'backgroundColor': '#afd5b8'}
    root = node(key, 'goal', goal, '; '.join(r[2] for r in rows), 'Goal')
    for row in rows:
        child = node(key, *row); link(child, root)

actor('refOrg', 'Referring organisation', 'Referral loop completed', [
    ('send', 'Submit referral and missing details', 'XRef_Prepare; XRef_ProvideInfo'),
    ('outcome', 'Receive and record outcome', 'XRef_Record')], True)
actor('medsec', 'Medical secretaries', 'Referral and letter support', [
    ('check', 'Check referral documents', 'SP01_CheckDocuments'),
    ('missing', 'Obtain missing information', 'SP01_RequestMissing; SP01_Catch_Supplementary', 'Task', 'Only when referral documents are incomplete.'),
    ('distribute', 'Check recipients and arrange dispatch', 'SP13_AdminProcess; SP13_Distribute'),
    ('record', 'Record distribution evidence', 'SP13_RecordDates')])
actor('consult', 'Consultants', 'Clinical decisions governed', [
    ('referral', 'Review referral and record decision', 'SP02_ClinicalReview; SP02_RequestInfo; SP02_NotifyRejected; SP02_NotifyRedirected'),
    ('assess', 'Assess patient and explain options', 'SP04_Assess'),
    ('consent', 'Record consent or refusal', 'SP04_RecordConsent; SP04_GW_Consent; SP10_Decide', 'Task', 'Consent permits request creation. Refusal is handled through P10; it is not simultaneous consent.'),
    ('request', 'Create authorised treatment request', 'SP04_CreateRequest; SP04_Validate', 'Task', 'Only with consent and a valid, complete request.'),
    ('letter', 'Prepare and approve clinic letter', 'SP13_Draft; SP13_Approve'),
    ('followup', 'Request indicated follow-up', 'SP13_NotifyFollowUp', 'Task', 'Only when follow-up is instructed. Preparing and approving the letter does not depend on booking completion.'),
    ('decision', 'Review cancellation and safety cases', 'SP10_Decide; SP09_Retrospective', 'Task', 'Cancellation decisions and retrospective authorisation after an urgent safety hold are separate conditional responsibilities.')])
actor('patient', 'Patient', 'Patient participation supported', [
    ('attend', 'Receive details and attend appointment', 'XPat_Attend'),
    ('decision', 'Communicate consent decision', 'SP04_Assess; SP04_RecordConsent; SP10_Record', 'Task', 'Inferred patient responsibility from the hospital consent or refusal interaction, not an added executable BPMN task.'),
    ('change', 'Request appointment change', 'XPat_Decide; XPat_SendChange'),
    ('cancel', 'Report appointment cancellation', 'XPat_Decide; XPat_SendCancel'),
    ('enquire', 'Raise question or concern', 'XPat_Enquire')], True)
actor('outpt', 'Outpatient bookings', 'Appointment service supported', [
    ('slot', 'Search and handle slot outcome', 'SP03_RequestSlot; SP03_Catch_Slot; SP03_NoSlotReview; SP03_BookNextAvailable', 'Task', 'Coordinate external scheduling. Book normally or record and escalate an exception according to availability.'),
    ('confirm', 'Confirm booking and send details', 'SP03_ConfirmBooking; SP03_SendLetter'),
    ('contact', 'Arrange timely patient contact', 'SP03_GW_TwoWeek; SP03_EscalateContact', 'Task', 'Telephone contact is performed by Call Handling when required; failed attempts are escalated.'),
    ('change', 'Rebook or review change request', 'SP03B_RequestSlot; SP03B_Rebook; SP03B_Notify; SP03B_Review', 'Task', 'P03B event branch; avoid duplicate bookings and review unresolved requests.'),
    ('followup', 'Book requested follow-up', 'SP14_RequestSlot; SP14_Book; SP14_Inform'),
    ('exception', 'Escalate booking and contact issues', 'SP03_NoSlotReview; SP03_EscalateContact; SP14_Highlight')])
actor('callteam', 'Call handling', 'Patient contact supported', [
    ('phone', 'Call patient and record outcome', 'SP03_PhoneContact'),
    ('classify', 'Record and classify enquiry', 'SP12_Record'),
    ('answer', 'Answer or route administrative query', 'SP12_Answer; SP12_Transfer'),
    ('clinical', 'Route clinical enquiry to CNS', 'SP12_GW_Class; SP12_ReferCNS', 'Task', 'Routing abstraction: CNS, not Call Handling, assesses clinical urgency.'),
    ('financial', 'Answer or refer financial query', 'SP12_AnswerFin; SP12_GW_Info; SP12_ReferFinance', 'Task', 'Answer only from authorised information; otherwise obtain Finance response.'),
    ('cancel', 'Record cancellation and route response', 'SP10_Record; SP10_NotifyRebook; SP10_NotifyReferrer'),
    ('close', 'Record handling and resolution', 'SP12_Close')])
actor('extTreat', 'External diagnostics and treatment provider', 'External service capacity and results provided', [
    ('availability', 'Return external resource availability', 'SP05_RequestAvailability; XExt_Check; XExt_SendAvail', 'Task', 'The BPMN HTTP JSON connector requests availability; this provider task abstracts the external response. The old message catch is absent from the current process.'),
    ('released', 'Notify released external slot', 'XExt_SendReleased'),
    ('lab', 'Perform test and return result', 'XExt_Test; XExt_SendLab', 'Task', 'External laboratory responsibility follows the BPMN participant and message flows.')], True)
A['extTreat']['customProperties']['Full actor name'] = 'External Treatment / Laboratory / Imaging Service'
actor('scheduler', 'External Scheduling Service', 'Appointment capacity coordinated', [
    ('search', 'Search appointment slots', 'XSched_Search'),
    ('return', 'Return proposed appointment slots', 'XSched_SendResult')], True)
actor('corr', 'External Correspondence Service', 'Correspondence dispatched and acknowledged', [
    ('dispatch', 'Prepare and despatch correspondence', 'XCorr_Prepare'),
    ('ack', 'Acknowledge clinic letter dispatch', 'XCorr_Ack')], True)
actor('trtbook', 'Treatment bookings', 'Treatment resources coordinated', [
    ('validate', 'Accept validated booking request', 'SP04_Validate; SP05_GW_Resource', 'Task', 'Booking intake uses the Consultants lane validation result; it does not take clinical authorisation ownership.'),
    ('internal', 'Book hospital facility', 'SP05_BookInternal', 'Task', 'Internal resource route only.'),
    ('external', 'Coordinate external availability', 'SP05_RequestAvailability; SP05_BookExternal', 'Task', 'External resource route only; the HTTP JSON connector returns availability and a successful check creates a provisional booking.'),
    ('pending', 'Keep pending and prevent duplicates', 'SP05_NotifyPending; SP05_Catch_Released', 'Task', 'When external capacity is unavailable; resume after slot release.')])
actor('clinical', 'Clinical team', 'Treatment safety supported', [
    ('fitness', 'Obtain bloods and assess fitness', 'SP08_RequestBloods; SP08_Catch_Lab; SP08_Review'),
    ('cycle', 'Record cycle and review continuation', 'SP08_Administer; SP08_GW_More; SP08_Catch_NextDue', 'Task', 'Administer only when fit; continue only when another cycle is required.'),
    ('delay', 'Record delay and request plan change', 'SP08_DelayHandling; SP08_NotifyChange; SP08_Catch_PlanUpdated'),
    ('hold', 'Apply immediate safety hold', 'SP09_ExecuteHold', 'Task', 'Apply immediately on the urgent route. Obtain Consultant authorisation separately afterwards.'),
    ('authorise', 'Obtain retrospective authorisation', 'SP09_ExecuteHold; SP09_Retrospective', 'Task', 'After the immediate safety hold. The hold itself does not wait for this authorisation; this receiving task abstracts the clinical team obtaining the Consultant decision.'),
    ('change', 'Validate and apply treatment change', 'SP09_ValidateFormal; SP09_ApplyChange; SP09_NotifyPlanUpdated', 'Task', 'Apply only when formal authorisation is valid; invalid requests are rejected.'),
    ('financial', 'Refer financial changes and refunds', 'SP09_GW_Financial; SP09_FinanceReview; SP09_NotifyRefund', 'Task', 'Coordination only; Finance owns the financial review and refund decision.'),
    ('review', 'Review pathway and request clinic letter', 'SP10_ClinicalReview; SP08_NotifyLetter')])
actor('fundorg', 'Funding organisation', 'Funding decision available', [
    ('assess', 'Assess funding application', 'XFund_Assess'),
    ('approval', 'Return funding approval decision', 'XFund_Send')], True)
actor('finance', 'Finance team', 'Financial cases controlled', [
    ('funding', 'Determine funding and record approval', 'SP06_Determine; SP06_RecordExemption; SP06_RequestApproval; SP06_RecordApproval', 'Task', 'Record exemption or insurer approval as applicable; these are alternative funding routes.'),
    ('payment', 'Request payment and record result', 'SP07_CalculateCharge; SP07_SendPaymentRequest; SP07_RecordTransaction', 'Task', 'Record transaction status, reference, date and amount. Never record card data.'),
    ('investigate', 'Investigate ambiguous payment', 'SP07_MarkInvestigation; SP07_Investigate', 'Task', 'No automatic duplicate payment request while transaction status is uncertain.'),
    ('resolve', 'Retry payment or record exception', 'SP07_DecideRetry; SP07_RecordOverride; SP07_ReferFinance', 'Task', 'Retry or record the authorised urgent clinical exception with reason; follow up outstanding finance.'),
    ('confirm', 'Confirm payment-cleared booking', 'SP07_ConfirmBooking', 'Task', 'Normal payment-complete route; exception handling remains a separate capability.'),
    ('refund', 'Decide and reconcile refund', 'SP11_Determine; SP11_SendRefund; SP11_Record; SP11_RecordNone', 'Task', 'Choose a full refund, a partial refund or no refund according to financial authority. No-refund decisions retain their reason.'),
    ('enquiry', 'Respond to financial enquiry', 'SP12_ReferFinance'),
    ('change', 'Review treatment-change finances', 'SP09_FinanceReview'),
    ('notice', 'Request payment issue notice', 'SP07_NotifyIssue')])
actor('psp', 'Payment provider', 'Secure transactions supported', [
    ('payment', 'Process payment and return status', 'XPSP_Process; XPSP_SendStatus'),
    ('refund', 'Execute refund and return result', 'XPSP_Refund; XPSP_SendRefund')], True)
actor('cnst', 'CNS team', 'Clinical enquiries supported', [
    ('priority', 'Assess clinical urgency', 'SP12_AssessPriority; SP12_FlagUrgent'),
    ('respond', 'Respond to clinical enquiry', 'SP12_ReferCNS')])
actor('pathway', 'Pathway coordinators', 'Pathway oversight supported', [
    ('monitor', 'Monitor outstanding pathway items', 'SP15_Scan'),
    ('letter', 'Remind Consultant of overdue letter', 'SP13_Reminder; SP13_Timer7d', 'Task', 'Seven-day letter preparation timer is non-interrupting; reminders do not replace approval.'),
    ('exceptions', 'Review highlighted exceptions', 'SP03_EscalateContact; SP14_Highlight; SP03B_Review', 'Task', 'Inferred receiving responsibility from tasks explicitly referring cases to the pathway team.'),
    ('escalate', 'Refer unresolved delays to management', 'SP15_EscalateManager; SP15_EscalateHigher'),
    ('report', 'Generate pathway management reports', 'SP15_Generate')])
actor('adminmgmt', 'Administrative management', 'Operational governance supported', [
    ('escalation', 'Resolve or escalate persistent delay', 'SP15_EscalateManager; SP15_EscalateHigher'),
    ('access', 'Verify authority and apply access', 'SP16_VerifyAuth; SP16_ApplyAccess'),
    ('downtime', 'Record downtime and reconcile records', 'SP16_RecordDowntime; SP16_Reconcile'),
    ('audit', 'Write attributable audit record', 'SP16_Audit')])

# Make the actor boundary traceable to the eight BPMN collaboration participants.
participant_ids = {
    'refOrg': 'Participant_Referrer', 'medsec': 'Participant_Hospital',
    'consult': 'Participant_Hospital', 'patient': 'Participant_Patient',
    'outpt': 'Participant_Hospital', 'callteam': 'Participant_Hospital',
    'extTreat': 'Participant_ExtTreat', 'trtbook': 'Participant_Hospital',
    'clinical': 'Participant_Hospital', 'fundorg': 'Participant_Funder',
    'finance': 'Participant_Hospital', 'psp': 'Participant_PSP',
    'cnst': 'Participant_Hospital', 'pathway': 'Participant_Hospital',
    'adminmgmt': 'Participant_Hospital', 'scheduler': 'Participant_Scheduling',
    'corr': 'Participant_Correspondence'}
for key, participant_id in participant_ids.items():
    A[key]['customProperties']['BPMN participant ID'] = participant_id

def dep(key, label, s, t, refs, typ='Resource'):
    i = 'dep_' + key
    s, t = 'n_' + s, 'n_' + t
    M['dependencies'].append({'id': i, 'type': 'istar.' + typ, 'text': label, 'source': s, 'target': t,
        'x': 0, 'y': 0, 'customProperties': {'Source BPMN IDs': refs, 'Full wording': label}})
    M['display'][i] = {'width': 190, 'height': 62, 'backgroundColor': '#d6ead7'}
    link(s, i, 'DependencyLink'); link(i, t, 'DependencyLink')

dep('documents', 'Referral and supporting information', 'medsec_check', 'refOrg_send', 'SP01_CheckDocuments; XRef_Prepare')
dep('checked', 'Checked referral documents', 'consult_referral', 'medsec_check', 'SP01_CheckDocuments; SP02_ClinicalReview')
dep('outcome', 'Recorded referral outcome', 'refOrg_outcome', 'consult_referral', 'SP02_NotifyRejected; SP02_NotifyRedirected; XRef_Record')
dep('approved', 'Approved clinic letter', 'medsec_distribute', 'consult_letter', 'SP13_Approve; SP13_Distribute')
dep('appointment', 'Confirmed appointment details', 'patient_attend', 'outpt_confirm', 'SP03_SendLetter; XPat_Attend')
dep('telephone', 'Recorded telephone outcome', 'outpt_contact', 'callteam_phone', 'SP03_PhoneContact; SP03_EscalateContact')
dep('attendance', 'Attend clinical appointment', 'consult_assess', 'patient_attend', 'XPat_Attend; SP04_Assess', 'Task')
dep('consent', 'Patient consent or refusal', 'consult_consent', 'patient_decision', 'SP04_GW_Consent; SP04_RecordConsent; SP10_Record')
dep('request', 'Authorised treatment request', 'trtbook_validate', 'consult_request', 'SP04_CreateRequest; SP04_Validate')
dep('availability', 'External resource availability', 'trtbook_external', 'extTreat_availability', 'SP05_RequestAvailability; XExt_Check; XExt_SendAvail; SP05_BookExternal')
dep('bloods', 'Pre-cycle blood test results', 'clinical_fitness', 'extTreat_lab', 'SP08_RequestBloods; XExt_Test; XExt_SendLab; SP08_Catch_Lab')
dep('appointmentSlots', 'Proposed appointment slots', 'outpt_slot', 'scheduler_return', 'SP03_RequestSlot; SP03_Catch_Slot; XSched_Search; XSched_SendResult')
dep('changeSlots', 'Appointment change slot', 'outpt_change', 'scheduler_return', 'SP03B_RequestSlot; SP03B_Catch_Result; XSched_Search; XSched_SendResult')
dep('followupSlots', 'Follow-up appointment slot', 'outpt_followup', 'scheduler_return', 'SP14_RequestSlot; SP14_Catch_Result; XSched_Search; XSched_SendResult')
dep('appointmentDispatch', 'Appointment correspondence dispatch', 'outpt_confirm', 'corr_dispatch', 'SP03_SendLetter; SP03B_Notify; SP14_Inform; XCorr_Prepare')
dep('clinicDispatch', 'Clinic letter dispatch', 'medsec_distribute', 'corr_dispatch', 'SP13_Distribute; XCorr_Prepare')
dep('dispatchAck', 'Clinic letter dispatch acknowledgement', 'medsec_record', 'corr_ack', 'SP13_RecordDates; XCorr_Ack')
dep('paymentNotice', 'Payment issue notice', 'finance_notice', 'corr_dispatch', 'SP07_NotifyIssue; XCorr_Prepare')
dep('retrospective', 'Retrospective safety authorisation', 'clinical_authorise', 'consult_decision', 'SP09_ExecuteHold; SP09_Retrospective')
dep('funding', 'Funding approval and limits', 'finance_funding', 'fundorg_approval', 'SP06_RecordApproval; XFund_Send')
dep('payment', 'Payment status and reference', 'finance_payment', 'psp_payment', 'SP07_Catch_Status; XPSP_SendStatus')
dep('investigation', 'Verified transaction outcome', 'finance_investigate', 'psp_payment', 'SP07_Investigate; XPSP_SendStatus')
dep('refund', 'Refund execution result', 'finance_refund', 'psp_refund', 'SP11_Catch_Result; XPSP_SendRefund')
dep('cns', 'Clinical assessment and response', 'callteam_clinical', 'cnst_respond', 'SP12_ReferCNS; SP12_AssessPriority')
dep('financequery', 'Authorised financial response', 'callteam_financial', 'finance_enquiry', 'SP12_ReferFinance; SP12_AnswerFin')
dep('enquiry', 'Enquiry recorded and routed', 'patient_enquire', 'callteam_classify', 'XPat_Enquire; SP12_Record', 'Goal')
dep('followup', 'Arrange instructed follow-up', 'consult_followup', 'outpt_followup', 'SP13_NotifyFollowUp; SP14_Book', 'Task')
dep('letter', 'Overdue letter completion status', 'pathway_letter', 'consult_letter', 'SP13_Reminder; SP13_Approve')
dep('escalate', 'Management escalation response', 'pathway_escalate', 'adminmgmt_escalation', 'SP15_EscalateManager; SP15_EscalateHigher')
dep('cancel', 'Clinically approved pathway response', 'callteam_cancel', 'consult_decision', 'SP10_Decide; SP10_NotifyRebook')
dep('financialchange', 'Financial review outcome', 'clinical_financial', 'finance_change', 'SP09_FinanceReview')
dep('exception', 'Pathway exception review', 'outpt_exception', 'pathway_exceptions', 'SP03_EscalateContact; SP14_Highlight', 'Task')
dep('change', 'Handle appointment change request', 'patient_change', 'outpt_change', 'XPat_SendChange; SP03B', 'Task')
dep('cancellation', 'Record and route cancellation', 'patient_cancel', 'callteam_cancel', 'XPat_SendCancel; SP10_Record', 'Task')

# Preserve the exclusive resource alternatives explicitly in SR.
resource_goal = node('trtbook', 'resource', 'Required resource arranged', 'SP05_GW_Resource', 'Goal',
    'Internal and external resource routes are alternative means selected by resourceType; OR does not itself encode BPMN exclusivity.')
link(resource_goal, 'n_trtbook_goal')
for edge in M['links']:
    if edge['type']=='istar.AndRefinementLink' and edge['source'] in ('n_trtbook_internal', 'n_trtbook_external'):
        edge.update(target=resource_goal, type='istar.OrRefinementLink')

# Two explicit quality contributions; neither is an unsupported guarantee.
for a, text, child, refs in [('clinical', 'Patient safety', 'hold', 'SP09_ExecuteHold'),
                             ('finance', 'Payment traceability', 'payment', 'SP07_RecordTransaction')]:
    q = node(a, 'quality', text, refs, 'Quality')
    link('n_' + a + '_' + child, q, 'ContributionLink')
    M['links'][-1]['label'] = 'help'

# Fixed seed graph placement keeps strongly related actors near each other.
keys = list(A)
edges = [(owner[d['source']][6:], owner[d['target']][6:]) for d in M['dependencies']]
slots = [(110 + col * 1640, 140 + row * 780) for row in range(6) for col in range(3)]
rng = random.Random(22)
def score(order):
    p = {k: slots[j] for j, k in enumerate(order)}
    return sum(abs(p[s][0]-p[t][0]) + abs(p[s][1]-p[t][1]) for s,t in edges)
best = keys[:]; bestcost = score(best)
for restart in range(24):
    order = keys[:]; rng.shuffle(order); cost = score(order)
    for _ in range(1000):
        i,j = rng.sample(range(len(keys)), 2); order[i],order[j]=order[j],order[i]
        trial = score(order)
        if trial <= cost: cost = trial
        else: order[i],order[j]=order[j],order[i]
    if cost < bestcost: best,bestcost=order[:],cost
for j,k in enumerate(best):
    a=A[k]; a['x'],a['y']=slots[j]
    tasks=[n for n in a['nodes'] if n['type']=='istar.Task']
    for idx,n in enumerate(tasks): n.update(x=a['x']+30+(idx%4)*250,y=a['y']+250+(idx//4)*140)
    N['n_'+k+'_goal'].update(x=a['x']+280,y=a['y']+100)
    if 'n_'+k+'_quality' in N: N['n_'+k+'_quality'].update(x=a['x']+780,y=a['y']+540)
a = A['trtbook']
for key, x, y in [('resource', 405, 250), ('internal', 280, 390), ('external', 530, 390), ('pending', 780, 390)]:
    N['n_trtbook_'+key].update(x=a['x']+x, y=a['y']+y)
A = {a['id']:a for a in M['actors']}

# Validate source mappings and the native iStar dependency restrictions.
bids={e.get('id') for e in ET.parse(BPMN).iter() if e.get('id')}
participant_refs={e.get('id') for e in ET.parse(BPMN).iter() if e.tag.endswith('participant')}
assert set(participant_ids.values()) == participant_refs, 'Actor boundary must cover each BPMN participant'
for e in [*N.values(), *M['dependencies']]:
    for ref in e['customProperties']['Source BPMN IDs'].split('; '): assert ref in bids, ref
refined={l['target'] for l in M['links'] if 'Refinement' in l['type'] or 'Contribution' in l['type']}
assert all(N[d['source']]['type']==N[d['target']]['type']=='istar.Task' and owner[d['source']]!=owner[d['target']] and d['source'] not in refined for d in M['dependencies'])
refinements = collections.defaultdict(list)
for edge in M['links']:
    if 'Refinement' in edge['type']: refinements[edge['target']].append(edge)
assert all(len({edge['type'] for edge in edges})==1 for edges in refinements.values()), 'Mixed AND/OR parent'
assert all(len(edges)>=2 for edges in refinements.values() if edges[0]['type']=='istar.AndRefinementLink'), 'Singleton AND'
assert next(d for d in M['dependencies'] if d['id']=='dep_retrospective')['source']=='n_clinical_authorise'
assert next(d for d in M['dependencies'] if d['id']=='dep_cancellation')['target']=='n_callteam_cancel'

assert all('/' not in e['text'] for e in [*M['actors'], *N.values(), *M['dependencies']]), 'Slash in visible text'

# ROUTING

# Keep dependums outside all actors; endpoint proximity now uses tasks, not people.
def expand(b,v):return (b[0]-v,b[1]-v,b[2]+v,b[3]+v)
def overlap(a,b):return a[0]<b[2] and b[0]<a[2] and a[1]<b[3] and b[1]<a[3]
B={a['id']:(a['x']-20,a['y']-30,max(n['x']+200 for n in a['nodes']),max(n['y']+72 for n in a['nodes'])) for a in A.values()}
reserved=[expand(b,32) for b in B.values()]
for d in sorted(M['dependencies'],key=lambda d:math.dist((N[d['source']]['x'],N[d['source']]['y']),(N[d['target']]['x'],N[d['target']]['y']))):
 s,t=N[d['source']],N[d['target']];sx,sy=s['x']+95,s['y']+31;tx,ty=t['x']+95,t['y']+31;mx,my=(sx+tx)/2,(sy+ty)/2;best=None
 for y in range(140,4790,40):
  for x in range(50,4720,40):
   b=expand((x,y,x+190,y+62),30)
   if any(overlap(b,r) for r in reserved):continue
   cx,cy=x+95,y+31;score=abs(cx-sx)+abs(cy-sy)+abs(cx-tx)+abs(cy-ty)+.5*(abs(cx-mx)+abs(cy-my))
   if best is None or score<best[0]:best=(score,x,y,b)
 assert best,d['id']
 _,d['x'],d['y'],b=best;reserved.append(b)

# Route each leg through its owning actor's free space and around all other actors.
D={d['id']:d for d in M['dependencies']};E={**N,**D};used=[]
def simplify(pts):
 out=[]
 for p in pts:
  if out and p==out[-1]:continue
  while len(out)>1 and (out[-2][0]==out[-1][0]==p[0] or out[-2][1]==out[-1][1]==p[1]):out.pop()
  out.append(p)
 return out
def route(start,end,obs):
 xs=sorted({20,4930,start[0],end[0],*[v for b in obs for v in (b[0]-20,b[2]+20)]});ys=sorted({100,4830,start[1],end[1],*[v for b in obs for v in (b[1]-20,b[3]+20)]})
 xi={x:i for i,x in enumerate(xs)};yi={y:i for i,y in enumerate(ys)};s=(xi[start[0]],yi[start[1]]);e=(xi[end[0]],yi[end[1]]);q=[(0,0,*s,-1)];costs={(*s,-1):0};prev={};cache={};last=None
 def cost(a,b):
  key=tuple(sorted((a,b)))
  if key in cache:return cache[key]
  x1,y1=xs[a[0]],ys[a[1]];x2,y2=xs[b[0]],ys[b[1]]
  for l,t,r,bt in obs:
   if x1==x2 and l<x1<r and max(min(y1,y2),t)<min(max(y1,y2),bt):cache[key]=None;return None
   if y1==y2 and t<y1<bt and max(min(x1,x2),l)<min(max(x1,x2),r):cache[key]=None;return None
  extra=0
  for p1,p2 in used:
   if x1==x2==p1[0]==p2[0]:extra+=max(0,min(max(y1,y2),max(p1[1],p2[1]))-max(min(y1,y2),min(p1[1],p2[1])))*4
   elif y1==y2==p1[1]==p2[1]:extra+=max(0,min(max(x1,x2),max(p1[0],p2[0]))-max(min(x1,x2),min(p1[0],p2[0])))*4
   elif x1==x2 and p1[1]==p2[1] and min(p1[0],p2[0])<x1<max(p1[0],p2[0]) and min(y1,y2)<p1[1]<=max(y1,y2):extra+=350
   elif y1==y2 and p1[0]==p2[0] and min(p1[1],p2[1])<y1<max(p1[1],p2[1]) and min(x1,x2)<p1[0]<=max(x1,x2):extra+=350
  cache[key]=abs(x1-x2)+abs(y1-y2)+extra;return cache[key]
 while q:
  _,c,i,j,d=heapq.heappop(q);st=(i,j,d)
  if c!=costs.get(st):continue
  if (i,j)==e:last=st;break
  for ni,nj,nd in [(i-1,j,0),(i+1,j,0),(i,j-1,1),(i,j+1,1)]:
   if not(0<=ni<len(xs) and 0<=nj<len(ys)):continue
   step=cost((i,j),(ni,nj))
   if step is None:continue
   nc=c+step+(120 if d not in(-1,nd) else 0);ns=(ni,nj,nd)
   if nc<costs.get(ns,math.inf):costs[ns]=nc;prev[ns]=st;heapq.heappush(q,(nc+abs(xs[ni]-end[0])+abs(ys[nj]-end[1]),nc,*ns))
 if last is None:raise RuntimeError(('No route',start,end))
 pts=[]
 while last in prev:pts.append((xs[last[0]],ys[last[1]]));last=prev[last]
 pts.append(start);pts.reverse();return simplify(pts)
legs=[l for l in M['links'] if l['type']=='istar.DependencyLink']
legs.sort(key=lambda l:math.dist((E[l['source']]['x'],E[l['source']]['y']),(E[l['target']]['x'],E[l['target']]['y'])))
for index,l in enumerate(legs):
 sid,tid=l['source'],l['target'];nid=sid if sid in N else tid;aid=owner[nid]
 s,t=E[sid],E[tid];start=(s['x']+95,s['y']+31);end=(t['x']+95,t['y']+31)
 obs=[expand(b,14) for a,b in B.items() if a!=aid]
 obs += [expand((n['x'],n['y'],n['x']+190,n['y']+62),12) for n in A[aid]['nodes'] if n['id'] not in (sid,tid)]
 obs += [expand((d['x'],d['y'],d['x']+190,d['y']+62),12) for d in D.values() if d['id'] not in(sid,tid)]
 a=A[aid];obs.append((a['x']-30,a['y']-40,B[aid][2],a['y']+55))
 pts=route(start,end,obs);used.extend(zip(pts,pts[1:]));dense=[pts[0]]
 for p,q in zip(pts,pts[1:]):
  steps=max(1,math.ceil(math.dist(p,q)/45))
  dense.extend((p[0]+(q[0]-p[0])*k/steps,p[1]+(q[1]-p[1])*k/steps) for k in range(1,steps+1))
 def inside(p,n):return n['x']-5<=p[0]<=n['x']+195 and n['y']-5<=p[1]<=n['y']+67
 while dense and inside(dense[0],s):dense.pop(0)
 while dense and inside(dense[-1],t):dense.pop()
 M['display'][l['id']]={'vertices':[{'x':round(x,2),'y':round(y,2)} for x,y in dense]}
for l in M['links']:
 if l['type']=='istar.DependencyLink':continue
 s,t=N[l['source']],N[l['target']];aid=owner[s['id']]
 obs=[expand((n['x'],n['y'],n['x']+190,n['y']+62),12) for n in A[aid]['nodes'] if n['id'] not in(s['id'],t['id'])]
 pts=route((s['x']+95,s['y']+31),(t['x']+95,t['y']+31),obs);dense=[pts[0]]
 for p,q in zip(pts,pts[1:]):
  steps=max(1,math.ceil(math.dist(p,q)/45));dense.extend((p[0]+(q[0]-p[0])*k/steps,p[1]+(q[1]-p[1])*k/steps) for k in range(1,steps+1))
 while dense and inside(dense[0],s):dense.pop(0)
 while dense and inside(dense[-1],t):dense.pop()
 M['display'][l['id']]={'vertices':[{'x':round(x,2),'y':round(y,2)} for x,y in dense]}

(ROOT/'goalModel.txt').write_text(json.dumps(M,ensure_ascii=False,indent=2),encoding='utf8')
report={'actors':len(A),'externalActors':sum(a['customProperties']['Scope']=='External' for a in A.values()),'bpmnParticipants':len(participant_refs),'bpmnParticipantsMapped':True,'intentionalElements':len(N),'dependencies':len(M['dependencies']),'taskEndpoints':len(M['dependencies'])*2,'actorEndpoints':0,'allSourceIdsExist':True,'noRefinedDepender':True,'noMixedRefinements':True,'noSingletonAND':True,'explicitResourceAlternatives':True,'safetyHoldIndependentOfRetrospectiveAuthorisation':True,'slashFreeLabels':True,'sourceSha256':M['diagram']['customProperties']['Source SHA256']}
(OUT/'model-validation.json').write_text(json.dumps(report,indent=2),encoding='utf8')
print(json.dumps(report))

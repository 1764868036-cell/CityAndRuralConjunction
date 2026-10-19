"""Build the Camunda 8 presentation model from the deploy-verified HPAS model.

P01-P08 are flattened into the hospital pool. Event-driven branches remain
non-interrupting event subprocesses, shown collapsed in the overview with
lane-aware drill-down diagrams. The source model is not modified.
"""

from __future__ import annotations

from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from lxml import etree as ET

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "source/hpas.v1.bpmn"
OUT = ROOT / "hospital-patient-pathway.bpmn"
B = "http://www.omg.org/spec/BPMN/20100524/MODEL"
BD = "http://www.omg.org/spec/BPMN/20100524/DI"
DC = "http://www.omg.org/spec/DD/20100524/DC"
DI = "http://www.omg.org/spec/DD/20100524/DI"
Z = "http://camunda.org/schema/zeebe/1.0"
M = "http://camunda.org/schema/modeler/1.0"
Q = lambda n, s: f"{{{n}}}{s}"
MAIN = [f"SP{i:02d}" for i in range(1, 9)]
BRANCH = ["SP03B"] + [f"SP{i:02d}" for i in range(9, 17)]
ROLES = ["Pathway coordination", "Medical Secretaries", "Consultants",
         "Outpatient Bookings Team", "Call Handling Team",
         "Treatment & Chemotherapy Bookings Team", "Finance Team", "Clinical Team",
         "Event branches"]


def tag(el):
    return ET.QName(el).localname


def bounds(shape):
    b = shape.find(Q(DC, "Bounds"))
    return [float(b.get(k)) for k in ("x", "y", "width", "height")]


def set_bounds(shape, x, y, w, h):
    b = shape.find(Q(DC, "Bounds"))
    for k, v in zip(("x", "y", "width", "height"), (x, y, w, h)):
        b.set(k, f"{round(v, 2):g}")


tree = ET.parse(str(SRC))
root = tree.getroot()
root.set(Q(M, "executionPlatform"), "Camunda Cloud")
root.set(Q(M, "executionPlatformVersion"), "8.9.0")
process = root.xpath('//b:process[@id="Process_HPAS"]', namespaces={"b": B})[0]
plane = root.find(f".//{Q(BD, 'BPMNPlane')}")
shapes = {s.get("bpmnElement"): s for s in plane.findall(Q(BD, "BPMNShape"))}
edges = {e.get("bpmnElement"): e for e in plane.findall(Q(BD, "BPMNEdge"))}
old_rect = {k: bounds(v) for k, v in shapes.items()}


def drop_di(ref):
    if ref in shapes:
        plane.remove(shapes.pop(ref))
    if ref in edges:
        plane.remove(edges.pop(ref))


def flow_nodes(container):
    skip = {"laneSet", "sequenceFlow", "documentation", "extensionElements"}
    return [x for x in container if x.get("id") and tag(x) not in skip]


# Preserve the stage's role assignment before removing its wrapper.
role_by_node = {}
stage_by_node = {}
for sid in MAIN:
    sp = process.xpath(f'./b:subProcess[@id="{sid}"]', namespaces={"b": B})[0]
    for lane in sp.xpath('./b:laneSet/b:lane', namespaces={"b": B}):
        for ref in lane.findall(Q(B, "flowNodeRef")):
            role_by_node[ref.text] = lane.get("name")
    for node in flow_nodes(sp):
        if tag(node) not in {"startEvent", "endEvent"}:
            stage_by_node[node.get("id")] = sid
            role_by_node.setdefault(node.get("id"), next(
                (l.get("name") for l in sp.xpath('./b:laneSet/b:lane', namespaces={"b": B})),
                "Pathway coordination"))


# Flatten each sequential stage while retaining original task and flow IDs.
for sid in MAIN:
    sp = process.xpath(f'./b:subProcess[@id="{sid}"]', namespaces={"b": B})[0]
    incoming = [f for f in process.findall(Q(B, "sequenceFlow")) if f.get("targetRef") == sid]
    outgoing = [f for f in process.findall(Q(B, "sequenceFlow")) if f.get("sourceRef") == sid]
    assert len(outgoing) == 1, sid
    target = outgoing[0].get("targetRef")
    starts = sp.findall(Q(B, "startEvent"))
    assert len(starts) == 1, sid
    starts_out = [f for f in sp.findall(Q(B, "sequenceFlow")) if f.get("sourceRef") == starts[0].get("id")]
    assert len(starts_out) == 1, sid
    first = starts_out[0].get("targetRef")
    for f in incoming:
        f.set("targetRef", first)
    for f in sp.findall(Q(B, "sequenceFlow")):
        if f is starts_out[0]:
            drop_di(f.get("id"))
            continue
        if any(f.get("targetRef") == e.get("id") for e in sp.findall(Q(B, "endEvent"))):
            f.set("targetRef", target)
        process.append(f)
    for node in flow_nodes(sp):
        if tag(node) in {"startEvent", "endEvent"}:
            drop_di(node.get("id"))
        else:
            process.append(node)
    for lane in sp.xpath('./b:laneSet/b:lane', namespaces={"b": B}):
        drop_di(lane.get("id"))
    process.remove(outgoing[0])
    drop_di(outgoing[0].get("id"))
    process.remove(sp)
    drop_di(sid)

# A failed/cancelled payment must be reviewed before another charge attempt or
# a clinician-authorised urgent exception. The framework had the gateway but
# no human decision task to set its variable.
notify_issue = process.xpath('./b:*[@id="SP07_NotifyIssue"]', namespaces={"b": B})[0]
decide_retry = ET.Element(Q(B, "userTask"), id="SP07_DecideRetry",
                          name="Finance decides retry or requests urgent clinical exception")
ext = ET.SubElement(decide_retry, Q(B, "extensionElements"))
ET.SubElement(ext, Q(Z, "userTask"))
ET.SubElement(ext, Q(Z, "assignmentDefinition"), candidateGroups="finance")
process.insert(list(process).index(notify_issue) + 1, decide_retry)
retry_flow = process.xpath('./b:sequenceFlow[@id="Flow_113"]', namespaces={"b": B})[0]
retry_flow.set("targetRef", "SP07_DecideRetry")
ET.SubElement(process, Q(B, "sequenceFlow"), id="Flow_SP07_DecideRetry",
              sourceRef="SP07_DecideRetry", targetRef="SP07_GW_Retry")
shape = ET.SubElement(plane, Q(BD, "BPMNShape"), id="di_SP07_DecideRetry", bpmnElement="SP07_DecideRetry")
ET.SubElement(shape, Q(DC, "Bounds"), x="0", y="0", width="1", height="1")
shapes["SP07_DecideRetry"] = shape
x, y, _, _ = old_rect["SP07_NotifyIssue"]
set_bounds(shape, x + 100, y + 140, 150, 80)
old_rect["SP07_DecideRetry"] = [x + 100, y + 140, 150, 80]
stage_by_node["SP07_DecideRetry"] = "SP07"
role_by_node["SP07_DecideRetry"] = "Finance Team"


# The case leaves clinical urgency criteria to the stakeholders. A call handler
# records a concern; a qualified CNS decides its priority in a dedicated task.
sp12 = process.xpath('./b:subProcess[@id="SP12"]', namespaces={"b": B})[0]
flag = sp12.xpath('./b:serviceTask[@id="SP12_FlagUrgent"]', namespaces={"b": B})[0]
flag.set("name", "Highlight concern assessed as urgent by CNS")
for f in sp12.findall(Q(B, "sequenceFlow")):
    if f.get("id") == "Flow_178":
        f.set("targetRef", "SP12_AssessPriority")
assess = ET.Element(Q(B, "userTask"), id="SP12_AssessPriority", name="CNS assesses clinical enquiry priority")
ext = ET.SubElement(assess, Q(B, "extensionElements"))
ET.SubElement(ext, Q(Z, "userTask"))
ET.SubElement(ext, Q(Z, "assignmentDefinition"), candidateGroups="cns")
sp12.insert(list(sp12).index(flag), assess)
urgent_gw = ET.Element(Q(B, "exclusiveGateway"), id="SP12_GW_Urgency", name="CNS assessed urgent?")
sp12.insert(list(sp12).index(flag), urgent_gw)
ET.SubElement(sp12, Q(B, "sequenceFlow"), id="Flow_SP12_Assessed",
              sourceRef="SP12_AssessPriority", targetRef="SP12_GW_Urgency")
routine_flow = ET.SubElement(sp12, Q(B, "sequenceFlow"), id="Flow_SP12_Routine", name="routine",
                             sourceRef="SP12_GW_Urgency", targetRef="SP12_ReferCNS")
ET.SubElement(routine_flow, Q(B, "conditionExpression"), {Q("http://www.w3.org/2001/XMLSchema-instance", "type"): "bpmn:tFormalExpression"}).text = '= urgentConcern = false'
urgent_flow = ET.SubElement(sp12, Q(B, "sequenceFlow"), id="Flow_SP12_Urgent", name="urgent",
                             sourceRef="SP12_GW_Urgency", targetRef="SP12_FlagUrgent")
ET.SubElement(urgent_flow, Q(B, "conditionExpression"), {Q("http://www.w3.org/2001/XMLSchema-instance", "type"): "bpmn:tFormalExpression"}).text = '= urgentConcern = true'
lane12 = sp12.xpath('./b:laneSet/b:lane[contains(@name,"Clinical Nurse") or contains(@name,"CNS")]', namespaces={"b": B})
if not lane12:
    lane12 = sp12.xpath('./b:laneSet/b:lane', namespaces={"b": B})[-1:]
ET.SubElement(lane12[0], Q(B, "flowNodeRef")).text = "SP12_AssessPriority"
ET.SubElement(lane12[0], Q(B, "flowNodeRef")).text = "SP12_GW_Urgency"

# Add visual positions for the two new elements and spread the clinical branch.
new_positions = {
    "SP12_AssessPriority": (540, 7244, 150, 80),
    "SP12_GW_Urgency": (740, 7244, 50, 50),
    "SP12_FlagUrgent": (940, 7360, 150, 80),
    "SP12_ReferCNS": (1140, 7244, 150, 80),
    "SP12_Close": (1340, 6746, 150, 80),
    "SP12_End": (1540, 6746, 36, 36),
}
for nid, rect in new_positions.items():
    if nid not in shapes:
        sh = ET.SubElement(plane, Q(BD, "BPMNShape"), id=f"di_{nid}", bpmnElement=nid)
        ET.SubElement(sh, Q(DC, "Bounds"), x="0", y="0", width="1", height="1")
        shapes[nid] = sh
    set_bounds(shapes[nid], *rect)
    old_rect[nid] = list(rect)
for lid in ("Lane_SP12_0", "Lane_SP12_1", "Lane_SP12_2"):
    x, y, w, h = old_rect[lid]
    rect = (x, y, 1560, 250 if lid == "Lane_SP12_2" else h)
    set_bounds(shapes[lid], *rect)
    old_rect[lid] = list(rect)
for lane in sp12.xpath('./b:laneSet/b:lane', namespaces={"b": B}):
    for ref in list(lane.findall(Q(B, "flowNodeRef"))):
        if ref.text == "SP12_FlagUrgent":
            lane.remove(ref)
            ET.SubElement(lane12[0], Q(B, "flowNodeRef")).text = "SP12_FlagUrgent"

# The letter-preparation subprocess is a lower visual band in SP13, so give it
# a dedicated Consultant lane instead of leaving it outside the original lane.
sp13 = process.xpath('./b:subProcess[@id="SP13"]', namespaces={"b": B})[0]
for lane in sp13.xpath('./b:laneSet/b:lane', namespaces={"b": B}):
    for ref in list(lane.findall(Q(B, "flowNodeRef"))):
        if ref.text in {"SP13_PrepareScope", "SP13_Timer7d"}:
            lane.remove(ref)
letter_lane = ET.SubElement(sp13.find(Q(B, "laneSet")), Q(B, "lane"),
                            id="Lane_SP13_LetterPreparation", name="Consultants - letter preparation")
for nid in ("SP13_PrepareScope", "SP13_Timer7d"):
    ET.SubElement(letter_lane, Q(B, "flowNodeRef")).text = nid
shape = ET.SubElement(plane, Q(BD, "BPMNShape"), id="di_Lane_SP13_LetterPreparation",
                      bpmnElement="Lane_SP13_LetterPreparation", isHorizontal="true")
ET.SubElement(shape, Q(DC, "Bounds"), x="0", y="0", width="1", height="1")
shapes["Lane_SP13_LetterPreparation"] = shape
set_bounds(shape, 80, 8070, 1500, 315)
old_rect["Lane_SP13_LetterPreparation"] = [80, 8070, 1500, 315]


# Every human task gets a deployment-bound Camunda Form. The task ID is also the
# stable form ID, making its owner and business decision explicit in Tasklist.
for task in process.xpath('.//b:userTask', namespaces={"b": B}):
    ext = task.find(Q(B, "extensionElements"))
    if ext is None:
        ext = ET.Element(Q(B, "extensionElements"))
        task.insert(0, ext)
    if ext.find(Q(Z, "userTask")) is None:
        ext.insert(0, ET.Element(Q(Z, "userTask")))
    old = ext.find(Q(Z, "formDefinition"))
    if old is not None:
        ext.remove(old)
    ET.SubElement(ext, Q(Z, "formDefinition"), formId=f"hpas_{task.get('id').lower()}")


# Incoming alternatives merge directly at the decision gateway.
# Avoid a separate diamond used only to label the merge.

# The patient simulation pool uses a single start and a choice of three simple
# reply paths. Multiple blank starts in one process are rejected by the linter.
patient = root.xpath('//b:process[@id="Proc_Patient"]', namespaces={"b": B})[0]
patient_start = patient.xpath('./b:startEvent[@id="XPat_Start1"]', namespaces={"b": B})[0]
patient_choice = ET.Element(Q(B, "exclusiveGateway"), id="XPat_GW_Response",
                            name="Patient response", default="Flow_XPat_Choice_3")
patient.insert(list(patient).index(patient_start) + 1, patient_choice)
for start_id in ("XPat_Start2", "XPat_Start3"):
    start = patient.xpath(f'./b:startEvent[@id="{start_id}"]', namespaces={"b": B})[0]
    for f in list(patient.findall(Q(B, "sequenceFlow"))):
        if f.get("sourceRef") == start_id:
            patient.remove(f)
            drop_di(f.get("id"))
    patient.remove(start)
    drop_di(start_id)
first_flow = next(f for f in patient.findall(Q(B, "sequenceFlow")) if f.get("sourceRef") == "XPat_Start1")
first_flow.set("targetRef", "XPat_GW_Response")
for i, target in enumerate(("XPat_Attend", "XPat_Decide", "XPat_Enquire"), 1):
    choice_flow = ET.SubElement(patient, Q(B, "sequenceFlow"), id=f"Flow_XPat_Choice_{i}",
                                name=("attend", "respond", "enquire")[i - 1],
                                sourceRef="XPat_GW_Response", targetRef=target)
    if i < 3:
        ET.SubElement(choice_flow, Q(B, "conditionExpression"),
                      {Q("http://www.w3.org/2001/XMLSchema-instance", "type"): "bpmn:tFormalExpression"}).text = (
                          '= patientAction = "attend"' if i == 1 else '= patientAction = "decide"')
shape = ET.SubElement(plane, Q(BD, "BPMNShape"), id="di_XPat_GW_Response", bpmnElement="XPat_GW_Response")
ET.SubElement(shape, Q(DC, "Bounds"), x="0", y="0", width="1", height="1")
shapes["XPat_GW_Response"] = shape
set_bounds(shape, 190, 815, 50, 50)
old_rect["XPat_GW_Response"] = [190, 815, 50, 50]

# Correspondence is also an external participant: show its dispatch receipt
# back to the hospital instead of ending immediately after printing.
collab = root.find(Q(B, "collaboration"))
ack_msg = ET.Element(Q(B, "message"), id="Msg_CorrespondenceAck",
                     name="HPAS: Correspondence dispatch acknowledgement")
ET.SubElement(ET.SubElement(ack_msg, Q(B, "extensionElements")),
              Q(Z, "subscription"), correlationKey="= referralId")
root.insert(list(root).index(collab), ack_msg)
corr = root.xpath('//b:process[@id="Proc_Correspondence"]', namespaces={"b": B})[0]
ack = ET.Element(Q(B, "intermediateThrowEvent"), id="XCorr_Ack",
                 name="Acknowledge dispatch")
ET.SubElement(ack, Q(B, "messageEventDefinition"), id="XCorr_Ack_Def",
              messageRef="Msg_CorrespondenceAck")
corr.insert(list(corr).index(corr.xpath('./b:endEvent[@id="XCorr_End"]', namespaces={"b": B})[0]), ack)
corr.xpath('./b:sequenceFlow[@id="Flow_253"]', namespaces={"b": B})[0].set("targetRef", "XCorr_Ack")
ET.SubElement(corr, Q(B, "sequenceFlow"), id="Flow_XCorr_Ack",
              sourceRef="XCorr_Ack", targetRef="XCorr_End")
ET.SubElement(collab, Q(B, "messageFlow"), id="MF_XCorr_Ack", name="Dispatch receipt",
              sourceRef="XCorr_Ack", targetRef="SP13_RecordDates", messageRef="Msg_CorrespondenceAck")
shape = ET.SubElement(plane, Q(BD, "BPMNShape"), id="di_XCorr_Ack", bpmnElement="XCorr_Ack")
ET.SubElement(shape, Q(DC, "Bounds"), x="0", y="0", width="1", height="1")
shapes["XCorr_Ack"] = shape
set_bounds(shape, 2856, 724, 36, 36)
old_rect["XCorr_Ack"] = [2856, 724, 36, 36]
set_bounds(shapes["XCorr_End"], 2940, 724, 36, 36)
old_rect["XCorr_End"] = [2940, 724, 36, 36]


# Rebuild incoming/outgoing references. BPMN engines infer these from sequence
# flows, but explicit references make editors and structural checks reliable.
for cont in root.xpath('//b:process | //b:subProcess', namespaces={"b": B}):
    inc, out = defaultdict(list), defaultdict(list)
    for f in cont.findall(Q(B, "sequenceFlow")):
        out[f.get("sourceRef")].append(f.get("id"))
        inc[f.get("targetRef")].append(f.get("id"))
    for node in flow_nodes(cont):
        for x in list(node):
            if tag(x) in {"incoming", "outgoing"}:
                node.remove(x)
        idx = 1 if node.find(Q(B, "extensionElements")) is not None else 0
        for fid in inc[node.get("id")]:
            node.insert(idx, ET.Element(Q(B, "incoming")))
            node[idx].text = fid
            idx += 1
        for fid in out[node.get("id")]:
            node.insert(idx, ET.Element(Q(B, "outgoing")))
            node[idx].text = fid
            idx += 1


# Main pool lanes. All event subprocess blocks sit inside the Event branches
# lane, and each expanded block retains its own role lanes.
old_lane = process.find(Q(B, "laneSet"))
if old_lane is not None:
    for lane in old_lane.findall(Q(B, "lane")):
        drop_di(lane.get("id"))
    process.remove(old_lane)
lane_set = ET.Element(Q(B, "laneSet"), id="LaneSet_HPAS_Redesigned")
lane_map = {}
for i, role in enumerate(ROLES):
    lane_map[role] = ET.SubElement(lane_set, Q(B, "lane"), id=f"Lane_HPAS_Redesigned_{i}", name=role)
for node in flow_nodes(process):
    nid = node.get("id")
    role = "Event branches" if nid in BRANCH else role_by_node.get(nid, "Pathway coordination")
    ET.SubElement(lane_map[role], Q(B, "flowNodeRef")).text = nid
process.insert(0, lane_set)


# Layout the flattened main pathway stage by stage in role lanes.
role_h = {r: (280 if r != "Pathway coordination" else 150) for r in ROLES[:-1]}
role_y = {}
yy = 50
for role in ROLES[:-1]:
    role_y[role] = yy
    yy += role_h[role]
core_bottom = yy
stage_x = {}
xx = 230
for sid in MAIN:
    ids = [nid for nid, stage in stage_by_node.items() if stage == sid and nid in shapes]
    x0 = min(old_rect[n][0] for n in ids)
    x1 = max(old_rect[n][0] + old_rect[n][2] for n in ids)
    stage_x[sid] = (xx, x0)
    xx += x1 - x0 + 210
pool_w = max(xx + 220, 12800)
for sid in MAIN:
    ids = [nid for nid, stage in stage_by_node.items() if stage == sid and nid in shapes]
    local_min = defaultdict(lambda: 10**9)
    for nid in ids:
        local_min[role_by_node[nid]] = min(local_min[role_by_node[nid]], old_rect[nid][1])
    sx, original_min_x = stage_x[sid]
    for nid in ids:
        role = role_by_node[nid]
        x, y, w, h = old_rect[nid]
        set_bounds(shapes[nid], sx + x - original_min_x,
                   role_y[role] + 35 + y - local_min[role], w, h)

# Top-level start / route nodes are placed on the coordination lane, close to
# their corresponding stage. They never occupy a clinical decision lane.
top_position = {
    "Top_Start": (35, 80), "Top_Catch_Referral": (105, 80),
    "GW_SP02_Exit": (stage_x["SP02"][0] + 1000, 83),
    "Join_SP02_Exit": (stage_x["SP02"][0] + 920, 83),
    "GW_SP03_Exit": (stage_x["SP03"][0] + 2350, 83),
    "Join_SP03_Exit": (stage_x["SP03"][0] + 2270, 83),
    "GW_SP04_Exit": (stage_x["SP04"][0] + 1550, 83),
    "Join_SP04_Exit": (stage_x["SP04"][0] + 1470, 83),
    "GW_SP06_Exit": (stage_x["SP06"][0] + 1250, 83),
    "Join_SP06_Exit": (stage_x["SP06"][0] + 1170, 83),
    "Top_End_ReferralClosed": (stage_x["SP02"][0] + 1090, 55),
    "Top_WaitClosure": (stage_x["SP08"][0] + 1700, 80),
    "Top_NotifyClosure": (stage_x["SP08"][0] + 1900, 55),
    "Top_End_Closed": (stage_x["SP08"][0] + 2120, 80),
}
for nid, (x, y) in top_position.items():
    if nid in shapes:
        _, _, w, h = bounds(shapes[nid])
        set_bounds(shapes[nid], x, y, w, h)

# Resolve the handful of overlaps inherited from the framework's compact
# stage layout after expanding it into full role lanes.
for nid, dy in {"SP03_GW_Attempts": 80, "SP03_EscalateContact": 80,
                "SP05_RequestAvailability": 50}.items():
    x, y, w, h = bounds(shapes[nid])
    set_bounds(shapes[nid], x, y + dy, w, h)
set_bounds(shapes["XPat_GW_Response"], 140, 815, 50, 50)
old_rect["XPat_GW_Response"] = [140, 815, 50, 50]
x, y, w, h = bounds(shapes["SP05_Catch_Released"])
set_bounds(shapes["SP05_Catch_Released"], x + 200, y, w, h)


# Keep the nine event-driven branches as collapsed, drill-down blocks on the
# overview. Their internal shapes are normalized to a local canvas and moved
# into one BPMNDiagram per subprocess further below.
branch_top = core_bottom + 55
branch_box_h = 100
branch_margin = 90
branch_gap = 44
branch_box_w = (pool_w - 2 * branch_margin - (len(BRANCH) - 1) * branch_gap) / len(BRANCH)
for idx, sid in enumerate(BRANCH):
    sp = process.xpath(f'./b:subProcess[@id="{sid}"]', namespaces={"b": B})[0]
    descendants = sp.xpath('.//*[@id]', namespaces={"b": B})
    ids = {x.get("id") for x in descendants if x.get("id")}
    shape_ids = [i for i in ids if i in shapes]
    x0 = min(old_rect[i][0] for i in shape_ids)
    y0 = min(old_rect[i][1] for i in shape_ids)
    dx, dy = 50 - x0, 50 - y0
    for nid in shape_ids:
        x, y, sw, sh = old_rect[nid]
        set_bounds(shapes[nid], x + dx, y + dy, sw, sh)
        old_rect[nid] = [x + dx, y + dy, sw, sh]
    for fid in ids:
        if fid in edges:
            for wp in edges[fid].findall(Q(DI, "waypoint")):
                wp.set("x", str(round(float(wp.get("x")) + dx, 2)))
                wp.set("y", str(round(float(wp.get("y")) + dy, 2)))
    x = branch_margin + idx * (branch_box_w + branch_gap)
    set_bounds(shapes[sid], x, branch_top, branch_box_w, branch_box_h)
    shapes[sid].set("isExpanded", "false")

event_bottom = branch_top + branch_box_h + 45
for role in ROLES:
    lane = lane_map[role]
    shape = ET.SubElement(plane, Q(BD, "BPMNShape"), id=f"di_{lane.get('id')}", bpmnElement=lane.get("id"), isHorizontal="true")
    shapes[lane.get("id")] = shape
    ET.SubElement(shape, Q(DC, "Bounds"), x="0", y="0", width="0", height="0")
    y = role_y.get(role, core_bottom + 30)
    h = role_h.get(role, event_bottom - core_bottom - 30)
    set_bounds(shape, 30, y, pool_w - 60, h)

# Relocate all external participants below the hospital pool; their simple
# request/response flows stay visually connected by message flows.
external_delta = event_bottom + 230 - 700
for p in root.xpath('//b:participant[@processRef!="Process_HPAS"]', namespaces={"b": B}):
    pid = p.get("id")
    proc_ref = p.get("processRef")
    refs = {pid}
    if proc_ref:
        extproc = root.xpath(f'//b:process[@id="{proc_ref}"]', namespaces={"b": B})[0]
        refs.update(x.get("id") for x in extproc.xpath('.//*[@id]', namespaces={"b": B}) if x.get("id"))
    for ref in refs:
        if ref in shapes:
            x, y, w, h = old_rect[ref]
            set_bounds(shapes[ref], x, y + external_delta, w, h)
        if ref in edges:
            for wp in edges[ref].findall(Q(DI, "waypoint")):
                wp.set("y", str(round(float(wp.get("y")) + external_delta, 2)))
    if pid == "Participant_ExtTreat":
        x, y, w, h = bounds(shapes[pid])
        set_bounds(shapes[pid], x, y, w + 40, h)

hospital_shape = shapes["Participant_Hospital"]
set_bounds(hospital_shape, 0, 0, pool_w, event_bottom + 100)


def center(ref):
    x, y, w, h = bounds(shapes[ref])
    return x + w / 2, y + h / 2


def route(edge, source, target):
    for wp in list(edge):
        edge.remove(wp)
    if source not in shapes or target not in shapes:
        return
    sx, sy = center(source)
    tx, ty = center(target)
    if abs(tx - sx) > 80:
        points = [(sx, sy), ((sx + tx) / 2, sy), ((sx + tx) / 2, ty), (tx, ty)]
    else:
        mid = min(sy, ty) - 55
        points = [(sx, sy), (sx, mid), (tx, mid), (tx, ty)]
    for x, y in points:
        ET.SubElement(edge, Q(DI, "waypoint"), x=str(round(x, 2)), y=str(round(y, 2)))


def overview_ref(ref):
    """Route collaboration message flows to the visible collapsed branch box."""
    if not ref:
        return ref
    node = root.xpath(f'//*[@id="{ref}"]')
    if not node:
        return ref
    current = node[0]
    while current is not None:
        if current.get("id") in BRANCH:
            return current.get("id")
        current = current.getparent()
    return ref


# New or retargeted edges need new waypoints. All existing branch edges were
# translated with their subprocess and keep their original route.
# Both urgent and routine treatment changes must pass formal authorisation.
# The retrospective route previously bypassed its gateway and could trigger a
# no-retries worker incident when the form recorded false.
flow_141 = process.xpath('.//b:sequenceFlow[@id="Flow_141"]', namespaces={"b": B})[0]
flow_141.set("targetRef", "SP09_GW_Formal")
gateway_formal = process.xpath('.//b:exclusiveGateway[@id="SP09_GW_Formal"]', namespaces={"b": B})[0]
apply_change = process.xpath('.//b:serviceTask[@id="SP09_ApplyChange"]', namespaces={"b": B})[0]
incoming = ET.Element(Q(B, "incoming"))
incoming.text = "Flow_141"
last_incoming = max((i for i, child in enumerate(gateway_formal)
                     if child.tag == Q(B, "incoming")), default=-1)
gateway_formal.insert(last_incoming + 1, incoming)
incoming_141 = apply_change.find(Q(B, "incoming"))
if incoming_141 is not None and incoming_141.text == "Flow_141":
    apply_change.remove(incoming_141)

branch_edge_ids = {x.get("id") for sid in BRANCH for x in process.xpath(
    f'./b:subProcess[@id="{sid}"]//b:sequenceFlow', namespaces={"b": B})}
for f in root.xpath('//b:sequenceFlow', namespaces={"b": B}):
    fid = f.get("id")
    if fid in branch_edge_ids and fid in edges and fid not in {"Flow_178", "Flow_185", "Flow_186"}:
        continue
    edge = edges.get(fid)
    if edge is None:
        edge = ET.SubElement(plane, Q(BD, "BPMNEdge"), id=f"di_{fid}", bpmnElement=fid)
        edges[fid] = edge
    route(edge, f.get("sourceRef"), f.get("targetRef"))
for f in root.xpath('//b:messageFlow', namespaces={"b": B}):
    edge = edges.get(f.get("id"))
    if edge is None:
        edge = ET.SubElement(plane, Q(BD, "BPMNEdge"),
                             id=f"di_{f.get('id')}", bpmnElement=f.get("id"))
        edges[f.get("id")] = edge
    route(edge, overview_ref(f.get("sourceRef")), overview_ref(f.get("targetRef")))


# Put each branch's detailed flow and lane DI on its own plane. This preserves
# the executable event-subprocess semantics while making the overview compact
# and the subprocess contents navigable in BPMN Modeler.
main_diagram = plane.getparent()
for sid in BRANCH:
    sp = process.xpath(f'./b:subProcess[@id="{sid}"]', namespaces={"b": B})[0]
    ids = {x.get("id") for x in sp.xpath('.//*[@id]', namespaces={"b": B}) if x.get("id")}
    diagram = ET.SubElement(root, Q(BD, "BPMNDiagram"), id=f"BPMNDiagram_{sid}",
                            name=sp.get("name"))
    child_plane = ET.SubElement(diagram, Q(BD, "BPMNPlane"),
                                id=f"BPMNPlane_{sid}", bpmnElement=sid)
    for ref in ids:
        shape = shapes.get(ref)
        if shape is not None and shape.getparent() is plane:
            plane.remove(shape)
            child_plane.append(shape)
        edge = edges.get(ref)
        if edge is not None and edge.getparent() is plane:
            plane.remove(edge)
            child_plane.append(edge)


# Label unnamed technical start/end events for review clarity.
for node in root.xpath('//b:startEvent | //b:endEvent', namespaces={"b": B}):
    if not node.get("name"):
        node.set("name", "Start" if tag(node) == "startEvent" else "End")

OUT.parent.mkdir(parents=True, exist_ok=True)
tree.write(str(OUT), encoding="utf-8", xml_declaration=True, pretty_print=True)
print(f"Wrote {OUT}")
print(f"Main stages flattened: {len(MAIN)}; event subprocesses: {len(BRANCH)}")

# Apply the taller, narrower presentation layout after generating the model.
import subprocess, sys
subprocess.run([sys.executable, str(ROOT / 'tools/compact_layout.py')], check=True)

subprocess.run([sys.executable, str(ROOT / 'tools/optimize_routes.py')], check=True)
subprocess.run([sys.executable, str(ROOT / 'tools/apply_sp05_rest.py')], check=True)

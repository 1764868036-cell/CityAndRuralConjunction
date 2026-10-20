#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Semantic self-check for hpas.v1.bpmn (structure-level, deploy-oriented)."""
import sys
import xml.etree.ElementTree as ET

B = "http://www.omg.org/spec/BPMN/20100524/MODEL"
Z = "http://camunda.org/schema/zeebe/1.0"
DI = "http://www.omg.org/spec/BPMN/20100524/DI"
DC = "http://www.omg.org/spec/DD/20100524/DC"
DINS = "http://www.omg.org/spec/DD/20100524/DI"

Q = lambda ns, t: "{%s}%s" % (ns, t)
SRC = sys.argv[1] if len(sys.argv) > 1 else "hpas.v1.bpmn"
tree = ET.parse(SRC)
root = tree.getroot()

errors = []
warns = []


def err(msg):
    errors.append(msg)


# ---- 1. unique ids ----
ids = {}
for el in root.iter():
    i = el.get("id")
    if i:
        if i in ids:
            err("duplicate id: %s" % i)
        ids[i] = el.tag.split("}")[-1]

# ---- collect messages ----
messages = {}
for m in root.findall(Q(B, "message")):
    sub = m.find(Q(B, "extensionElements") + "/" + Q(Z, "subscription"))
    if sub is None or not sub.get("correlationKey"):
        err("message %s missing zeebe:subscription correlationKey" % m.get("id"))
    messages[m.get("id")] = m.get("name")

# ---- collaboration ----
collab = root.find(Q(B, "collaboration"))
participants = {p.get("id"): p.get("name") for p in collab.findall(Q(B, "participant"))}
mflows = collab.findall(Q(B, "messageFlow"))
for mf in mflows:
    for ref in (mf.get("sourceRef"), mf.get("targetRef")):
        if ref not in participants and ref not in ids:
            err("messageFlow %s dangling ref %s" % (mf.get("id"), ref))
    if mf.get("messageRef") not in messages:
        err("messageFlow %s unknown messageRef %s" % (mf.get("id"), mf.get("messageRef")))

# ---- processes ----
procs = root.findall(Q(B, "process"))
main = [p for p in procs if p.get("id") == "Process_HPAS"]
if len(main) != 1:
    err("main process missing")
proc = main[0]

FLOWNODE_TAGS = {"startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
                 "boundaryEvent", "exclusiveGateway", "parallelGateway", "eventBasedGateway",
                 "inclusiveGateway", "userTask", "serviceTask", "sendTask", "receiveTask",
                 "manualTask", "scriptTask", "businessRuleTask", "task", "subProcess", "callActivity"}


def check_scope(el, scope_id, in_event_parent=False):
    """recursively check a process/subProcess scope"""
    nodes = {}
    flows = []
    subs = []
    lanes = set()
    laneset = el.find(Q(B, "laneSet"))
    if laneset is not None:
        for lane in laneset.findall(Q(B, "lane")):
            lanes.add(lane.get("id"))
            refs = [r.text for r in lane.findall(Q(B, "flowNodeRef"))]
            for r in refs:
                if r not in {e.get("id") for e in el if e.tag.split('}')[-1] in FLOWNODE_TAGS}:
                    err("lane %s references unknown flowNode %s" % (lane.get("id"), r))
    for e in el:
        tag = e.tag.split("}")[-1]
        if tag in FLOWNODE_TAGS:
            nodes[e.get("id")] = e
        elif tag == "sequenceFlow":
            flows.append(e)
        elif tag in ("incoming", "outgoing"):
            pass
    is_event_sub = el.get("triggeredByEvent") == "true"
    # start events
    starts = [e for e in nodes.values() if e.tag.endswith("startEvent")]
    if is_event_sub:
        if len(starts) != 1:
            err("%s: event subprocess must have exactly 1 start event (has %d)" % (scope_id, len(starts)))
        if starts:
            se = starts[0]
            if se.get("isInterrupting") != "false":
                err("%s: event subprocess start should be non-interrupting (isInterrupting=false)" % scope_id)
            defs = se.findall(Q(B, "messageEventDefinition"))
            if len(defs) != 1:
                err("%s: event subprocess start must have one messageEventDefinition" % scope_id)
            else:
                if defs[0].get("messageRef") not in messages:
                    err("%s: unknown messageRef" % scope_id)
        if laneset is not None:
            warn = "event subprocess %s has laneSet (unusual for Zeebe)" % scope_id
            warns.append(warn)
    else:
        if scope_id != "Process_HPAS" or True:
            nstarts = [s for s in starts if not s.findall(Q(B, "messageEventDefinition"))]
            if len(nstarts) != 1:
                err("%s: must have exactly 1 none start event (has %d)" % (scope_id, len(nstarts)))
    for fid, fe in [(e.get("id"), e) for e in flows]:
        s, t = fe.get("sourceRef"), fe.get("targetRef")
        if s not in nodes:
            err("flow %s: sourceRef %s not in scope %s" % (fid, s, scope_id))
        if t not in nodes:
            err("flow %s: targetRef %s not in scope %s" % (fid, t, scope_id))
        if fe.find(Q(B, "conditionExpression")) is not None:
            if not s.endswith("Gateway") and "Gateway" not in (nodes[s].tag.split("}")[-1] if s in nodes else ""):
                err("flow %s: condition on non-gateway source %s" % (fid, s))
    # incoming/outgoing consistency
    for nid, ne in nodes.items():
        tag = ne.tag.split("}")[-1]
        node_is_event_sub = (tag == "subProcess" and ne.get("triggeredByEvent") == "true")
        ins = [i.text for i in ne.findall(Q(B, "incoming"))]
        outs = [o.text for o in ne.findall(Q(B, "outgoing"))]
        real_in = [f.get("id") for f in flows if f.get("targetRef") == nid]
        real_out = [f.get("id") for f in flows if f.get("sourceRef") == nid]
        if sorted(ins) != sorted(real_in) and not node_is_event_sub:
            err("%s: incoming refs mismatch (%s)" % (nid, tag))
        if sorted(outs) != sorted(real_out) and not node_is_event_sub:
            err("%s: outgoing refs mismatch (%s)" % (nid, tag))
        if tag not in ("startEvent", "boundaryEvent") and not ins and not is_event_sub and not node_is_event_sub:
            err("%s (%s): no incoming flow" % (nid, tag))
        if tag not in ("endEvent", "boundaryEvent") and not outs and not is_event_sub and not node_is_event_sub:
            err("%s (%s): no outgoing flow" % (nid, tag))
        if tag in ("serviceTask", "sendTask"):
            td = ne.find(Q(B, "extensionElements") + "/" + Q(Z, "taskDefinition"))
            if td is None or not td.get("type"):
                err("%s: %s missing zeebe:taskDefinition" % (nid, tag))
        if tag == "userTask":
            ad = ne.find(Q(B, "extensionElements") + "/" + Q(Z, "assignmentDefinition"))
            if ad is None or not (ad.get("assignee") or ad.get("candidateGroups")):
                err("%s: userTask missing assignmentDefinition" % nid)
        if tag == "boundaryEvent":
            if not ne.get("attachedToRef"):
                err("%s: boundaryEvent missing attachedToRef" % nid)
        # catch events must reference a message
        if tag == "intermediateCatchEvent":
            d = ne.findall(Q(B, "messageEventDefinition"))
            if len(d) != 1:
                err("%s: catch event needs exactly one messageEventDefinition" % nid)
            else:
                if d[0].get("messageRef") not in messages:
                    err("%s: unknown messageRef" % nid)
    # nested subprocesses
    for e in nodes.values():
        if e.tag.endswith("subProcess"):
            check_scope(e, e.get("id"))
            subs.append(e.get("id"))
    return nodes, flows


top_nodes, top_flows = check_scope(proc, "Process_HPAS")

# ---- event subprocesses direct children of process ----
ev_subs = [e for e in top_nodes.values() if e.tag.endswith("subProcess") and e.get("triggeredByEvent") == "true"]
print("event subprocesses:", len(ev_subs), [s.get("id") for s in ev_subs])

# ---- DI coverage ----
plane = root.find(".//" + Q(DI, "BPMNPlane"))
shapes = {s.get("bpmnElement") for s in plane.findall(Q(DI, "BPMNShape"))}
edges = {e.get("bpmnElement") for e in plane.findall(Q(DI, "BPMNEdge"))}
missing_shapes = []
for e in root.iter():
    tag = e.tag.split("}")[-1]
    if tag in FLOWNODE_TAGS | {"participant", "lane"} and e.get("id") and e.get("id") not in shapes:
        missing_shapes.append(e.get("id"))
if missing_shapes:
    err("DI missing shapes: %s" % missing_shapes[:10])
missing_edges = [f.get("id") for f in root.iter(Q(B, "sequenceFlow")) if f.get("id") not in edges]
if missing_edges:
    err("DI missing edges for flows: %s" % missing_edges[:10])
missing_mf_edges = [m.get("id") for m in mflows if m.get("id") not in edges]
if missing_mf_edges:
    err("DI missing edges for messageFlows: %s" % missing_mf_edges[:10])

print("messages:", len(messages), "participants:", len(participants))
print("WARN:", len(warns))
for w in warns:
    print("  -", w)
if errors:
    print("FAILED: %d errors" % len(errors))
    for e in errors:
        print("  ERROR:", e)
    sys.exit(1)
print("SEMANTIC CHECK PASSED")

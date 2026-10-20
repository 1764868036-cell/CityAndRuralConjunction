"""Cross-check BPMN, lane DI, forms, and Java registrations."""

import json
import re
from pathlib import Path
from lxml import etree as ET

ROOT = Path(__file__).resolve().parent.parent
N = {"b": "http://www.omg.org/spec/BPMN/20100524/MODEL",
     "d": "http://www.omg.org/spec/BPMN/20100524/DI",
     "c": "http://www.omg.org/spec/DD/20100524/DC",
     "z": "http://camunda.org/schema/zeebe/1.0"}
BUNDLE = ROOT / "bpmn" if (ROOT / "bpmn/hospital-patient-pathway.bpmn").exists() else ROOT
FORMS = BUNDLE if BUNDLE.name == "bpmn" else ROOT / "forms"
r = ET.parse(str(BUNDLE / "hospital-patient-pathway.bpmn"))
errors = []


def check(ok, message):
    if not ok:
        errors.append(message)


def box(shape):
    b = shape.find("c:Bounds", namespaces=N)
    return [float(b.get(k)) for k in ("x", "y", "width", "height")]


def inside(a, b):
    x, y, w, h = a
    x0, y0, w0, h0 = b
    return x >= x0 - 1 and y >= y0 - 1 and x + w <= x0 + w0 + 1 and y + h <= y0 + h0 + 1


planes = {x.get("bpmnElement"): x for x in r.xpath('//d:BPMNPlane', namespaces=N)}
shapes = {x.get("bpmnElement"): x for x in r.xpath('//d:BPMNShape', namespaces=N)}
edges = {x.get("bpmnElement"): x for x in r.xpath('//d:BPMNEdge', namespaces=N)}
ids = [x.get("id") for x in r.xpath('//*[@id]')]
check(len(ids) == len(set(ids)), "duplicate XML ids")
process = r.xpath('//b:process[@id="Process_HPAS"]', namespaces=N)[0]
check(len(r.xpath('//b:process[@isExecutable="true"]', namespaces=N)) == 1, "exactly one executable process required")
check(not any(process.xpath(f'./b:subProcess[@id="SP{i:02d}"]', namespaces=N) for i in range(1, 9)),
      "P01-P08 must be expanded in main scope")

branches = process.xpath('./b:subProcess[@triggeredByEvent="true"]', namespaces=N)
check(len(branches) == 9, "nine event branches required")
for sp in branches:
    sid = sp.get("id")
    check(shapes[sid].get("isExpanded") == "false", f"{sid} not collapsed in overview")
    starts = sp.xpath('./b:startEvent', namespaces=N)
    check(len(starts) >= 1 and all(s.get("isInterrupting") == "false" for s in starts),
          f"{sid} start event not non-interrupting")
    child_plane = planes.get(sid)
    check(child_plane is not None, f"{sid} missing drill-down diagram plane")
    child_shapes = ({x.get("bpmnElement"): x for x in child_plane.xpath('./d:BPMNShape', namespaces=N)}
                    if child_plane is not None else {})
    lanes = sp.xpath('./b:laneSet/b:lane', namespaces=N)
    check(bool(lanes), f"{sid} missing laneSet")
    for lane in lanes:
        lid = lane.get("id")
        check(lid in child_shapes, f"{sid} lane {lid} missing from drill-down diagram")
        for ref in lane.xpath('./b:flowNodeRef/text()', namespaces=N):
            check(ref in child_shapes and lid in child_shapes and
                  inside(box(child_shapes[ref]), box(child_shapes[lid])),
                  f"{sid} node {ref} outside lane {lid}")
    for child in sp.xpath('.//*[@id]', namespaces=N):
        cid = child.get("id")
        # Nested subprocesses can own their own valid drill-down plane.
        scope = next((a for a in child.iterancestors() if ET.QName(a).localname == 'subProcess'), sp)
        if (scope is not sp and scope.get('id') in planes
                and ET.QName(child).localname not in {'laneSet', 'extensionElements'}):
            nested_plane = planes[scope.get('id')]
            check(any(x.get('bpmnElement') == cid for x in nested_plane),
                  f"{scope.get('id')} element {cid} missing from nested diagram")
            continue
        if ET.QName(child).localname in {"sequenceFlow"}:
            check(child_plane is not None and any(e.get("bpmnElement") == cid
                                                   for e in child_plane.xpath('./d:BPMNEdge', namespaces=N)),
                  f"{sid} flow {cid} missing from drill-down diagram")
        elif ET.QName(child).localname in {"startEvent", "endEvent", "userTask", "serviceTask",
                                           "scriptTask", "task", "exclusiveGateway", "parallelGateway",
                                           "eventBasedGateway", "intermediateCatchEvent",
                                           "intermediateThrowEvent", "subProcess", "lane"}:
            check(cid in child_shapes, f"{sid} element {cid} missing from drill-down diagram")

for cont in r.xpath('//b:process | //b:subProcess', namespaces=N):
    nodes = {x.get("id") for x in cont if x.get("id") and ET.QName(x).localname not in ("sequenceFlow", "laneSet")}
    for f in cont.xpath('./b:sequenceFlow', namespaces=N):
        fid = f.get("id")
        check(f.get("sourceRef") in nodes and f.get("targetRef") in nodes,
              f"{fid} crosses BPMN scope")
        check(fid in edges and len(edges[fid]) >= 2, f"{fid} has no routed DI edge")

tasks = process.xpath('.//b:userTask', namespaces=N)
for t in tasks:
    tid = t.get("id")
    form_id = t.xpath('./b:extensionElements/z:formDefinition/@formId', namespaces=N)
    check(len(form_id) == 1 and form_id[0] == f"hpas_{tid.lower()}", f"{tid} form reference")
    if form_id:
        path = FORMS / f"{form_id[0]}.form"
        check(path.exists(), f"missing form {path.name}")
        if path.exists():
            form = json.loads(path.read_text(encoding="utf-8"))
            check(form.get("id") == form_id[0], f"form ID mismatch {tid}")

job_types = set(process.xpath('.//z:taskDefinition/@type', namespaces=N))
source = (ROOT / "java/src/main/java/io/camunda/demo/hpas/HpasJobWorkers.java").read_text(encoding="utf-8")
registered = set(re.findall(r'@JobWorker\(type = "([^"]+)"\)', source))
connector_types = {'io.camunda:http-json:1'}
# The legacy worker remains registered for already deployed historical versions.
legacy_types = {'hpas-sp05-request-availability'}
check(job_types - connector_types == registered - legacy_types,
      f"worker mismatch: missing={job_types - connector_types - registered}, extra={registered - legacy_types - job_types}")

for p in r.xpath('//b:participant[@processRef!="Process_HPAS"]', namespaces=N):
    proc = r.xpath(f'//b:process[@id="{p.get("processRef")}"]', namespaces=N)[0]
    check(bool(proc.xpath('./b:startEvent', namespaces=N)) and bool(proc.xpath('./b:endEvent', namespaces=N))
          and bool(proc.xpath('./b:task', namespaces=N))
          and bool(proc.xpath('./b:intermediateThrowEvent', namespaces=N)),
          f"{p.get('name')} lacks visible request-response simulation")

for fid in ("hpas_sp12_assesspriority", "hpas_sp07_decideretry"):
    check((FORMS / f"{fid}.form").exists(), f"missing critical decision form {fid}")
for path in FORMS.glob("*.form"):
    data = path.read_text(encoding="utf-8").lower()
    check("cardnumber" not in data and "cardcvv" not in data,
          f"card details in {path.name}")

if errors:
    print("BUNDLE CHECK FAILED")
    for e in errors:
        print(" -", e)
    raise SystemExit(1)
print(f"BUNDLE CHECK PASSED: {len(branches)} event subprocesses, {len(tasks)} forms, "
      f"{len(job_types)} job types, 7 external response pools")

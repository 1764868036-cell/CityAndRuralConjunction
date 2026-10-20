#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Render a BPMN overview DI plane to a light-theme SVG preview."""
import sys
from pathlib import Path
import html
import xml.etree.ElementTree as ET

B = "http://www.omg.org/spec/BPMN/20100524/MODEL"
DI = "http://www.omg.org/spec/BPMN/20100524/DI"
DC = "http://www.omg.org/spec/DD/20100524/DC"
DINS = "http://www.omg.org/spec/DD/20100524/DI"
Q = lambda ns, t: "{%s}%s" % (ns, t)

ROOT = Path(__file__).resolve().parent.parent
SRC = sys.argv[1] if len(sys.argv) > 1 else ROOT / "hpas.v1.bpmn"
OUT = sys.argv[2] if len(sys.argv) > 2 else ROOT / "hpas.v1-preview.svg"

root = ET.parse(SRC).getroot()

# ---------- model ----------
info = {}      # id -> (kind, name)
parent = {}    # child flowNode id -> parent process/subProcess id
evsub = set()
KINDS = ("startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
         "boundaryEvent", "exclusiveGateway", "parallelGateway", "eventBasedGateway",
         "userTask", "serviceTask", "sendTask", "task", "subProcess")


def walk(el, par):
    for e in el:
        tag = e.tag.split("}")[-1]
        if tag in KINDS:
            info[e.get("id")] = (tag, e.get("name") or "")
            parent[e.get("id")] = par
            if tag == "subProcess":
                if e.get("triggeredByEvent") == "true":
                    evsub.add(e.get("id"))
                walk(e, e.get("id"))
        elif tag == "lane":
            info[e.get("id")] = ("lane", e.get("name") or "")


for _p in root.iter(Q(B, "process")):
    walk(_p, _p.get("id"))
for el in root.iter():
    tag = el.tag.split("}")[-1]
    if tag == "participant":
        info[el.get("id")] = ("participant", el.get("name") or "")

collab = root.find(Q(B, "collaboration"))
mflows = [(m.get("id"), m.get("sourceRef"), m.get("targetRef")) for m in collab.findall(Q(B, "messageFlow"))]
flow_names = {f.get("id"): (f.get("name") or "") for f in root.iter(Q(B, "sequenceFlow"))}

# ---------- DI ----------
plane = root.find(".//" + Q(DI, "BPMNPlane"))
shapes = {}
for s in plane.findall(Q(DI, "BPMNShape")):
    b = s.find(Q(DC, "Bounds"))
    if b is not None:
        shapes[s.get("bpmnElement")] = (float(b.get("x")), float(b.get("y")),
                                        float(b.get("width")), float(b.get("height")),
                                        s.get("isExpanded"))
edges = []
for e in plane.findall(Q(DI, "BPMNEdge")):
    wps = [(float(w.get("x")), float(w.get("y"))) for w in e.findall(Q(DINS, "waypoint"))]
    edges.append((e.get("bpmnElement"), wps))

seq_edges = [(fid, wps) for fid, wps in edges
             if fid in flow_names and not fid.startswith("MF_")]
mf_edges = [(fid, wps) for fid, wps in edges if fid.startswith("MF_")]

W = max(x + w for x, y, w, h, _ in shapes.values()) + 40
H = max(y + h for x, y, w, h, _ in shapes.values()) + 40

O = []
A = O.append
A('<svg xmlns="http://www.w3.org/2000/svg" viewBox="-20 -20 %d %d" '
  'font-family="Segoe UI, Arial, sans-serif">' % (W, H))
A('<defs>')
A('<marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
  '<path d="M1 1L9 5L1 9" fill="none" stroke="#475569" stroke-width="1.6"/></marker>')
A('<marker id="arrm" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">'
  '<path d="M1 1L9 5L1 9" fill="none" stroke="#0F766E" stroke-width="1.6"/></marker>')
A('</defs>')
A('<rect x="-20" y="-20" width="%d" height="%d" fill="#FDFDFE"/>' % (W, H))
A('<text x="10" y="8" font-size="20" font-weight="600" fill="#0F172A">HPAS - Hospital Patient '
  'Administration System (hpas.v1 framework, Camunda 8)</text>')


def esc(s):
    return html.escape(s or "")


def wrap(name, maxlen=20):
    words = (name or "").split()
    lines, cur = [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > maxlen:
            lines.append(cur)
            cur = w
        else:
            cur = cur + (" " if cur else "") + w
    if cur:
        lines.append(cur)
    return lines[:4] or [""]


def draw_label(x, y, w, name, fs=10.5, fill="#1E293B", maxlines=4):
    lines = wrap(name, 22 if fs <= 10.5 else 26)
    if len(lines) > maxlines:
        lines = lines[:maxlines]
    n = len(lines)
    for i, ln in enumerate(lines):
        A('<text x="%.1f" y="%.1f" font-size="%.1f" fill="%s" text-anchor="middle">%s</text>'
          % (x + w / 2, y - n * 6 + 6 + i * 12, fs, fill, esc(ln)))


# ---------- pools & lanes ----------
for pid, (x, y, w, h, exp) in shapes.items():
    kind, name = info.get(pid, ("", ""))
    if kind == "participant":
        extern = pid != "Participant_Hospital"
        A('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" rx="6" fill="%s" stroke="%s" stroke-width="1.2"/>'
          % (x, y, w, h, "#F1F5F9" if extern else "#EEF2FF", "#94A3B8" if extern else "#6366F1"))
        A('<rect x="%.0f" y="%.0f" width="30" height="%.0f" fill="%s" stroke="%s" stroke-width="1.2"/>'
          % (x, y, h, "#E2E8F0" if extern else "#E0E7FF", "#94A3B8" if extern else "#6366F1"))
        cx = x + 15
        cy = y + h / 2
        A('<text x="%.0f" y="%.0f" font-size="12.5" font-weight="600" fill="#334155" '
          'text-anchor="middle" transform="rotate(-90 %.0f %.0f)">%s</text>'
          % (cx + 4, cy, cx + 4, cy, esc(name)))
    elif kind == "lane":
        A('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" fill="#FFFFFF" fill-opacity="0.55" '
          'stroke="#CBD5E1" stroke-width="0.8"/>' % (x, y, w, h))
        A('<text x="%.0f" y="%.0f" font-size="12" font-weight="600" fill="#475569" '
          'text-anchor="middle" transform="rotate(-90 %.0f %.0f)">%s</text>'
          % (x + 14, y + h / 2, x + 14, y + h / 2, esc(name)))

# ---------- shelf frames ----------
children_by_scope = {}
for nid, par in parent.items():
    if par != "Process_HPAS":
        children_by_scope.setdefault(par, []).append(nid)
for sc, kids in children_by_scope.items():
    if sc in shapes and shapes[sc][4] == "false":  # collapsed at top -> shelf below
        xs = [shapes[k][0] for k in kids if k in shapes]
        ys = [shapes[k][1] for k in kids if k in shapes]
        x2 = [shapes[k][0] + shapes[k][2] for k in kids if k in shapes]
        y2 = [shapes[k][1] + shapes[k][3] for k in kids if k in shapes]
        if not xs:
            continue
        x0, y0 = min(xs) - 60, min(ys) - 56
        x1, y1 = max(x2) + 40, max(y2) + 40
        kind, name = info[sc]
        A('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" rx="10" fill="none" stroke="#94A3B8" '
          'stroke-width="1" stroke-dasharray="7 5"/>' % (x0, y0, x1 - x0, y1 - y0))
        tag = "event subprocess" if sc in evsub else "subprocess"
        A('<text x="%.0f" y="%.0f" font-size="13.5" font-weight="600" fill="#334155">%s  '
          '<tspan font-weight="400" fill="#94A3B8" font-size="11">(%s internal view)</tspan></text>'
          % (x0 + 14, y0 - 8, esc(name), tag))

# ---------- sequence flow edges ----------
for fid, wps in seq_edges:
    pts = " ".join("%.0f,%.0f" % p for p in wps)
    lbl = flow_names.get(fid, "")
    A('<polyline points="%s" fill="none" stroke="#475569" stroke-width="1.3" marker-end="url(#arr)"/>' % pts)
    if lbl and len(wps) >= 2:
        mx = (wps[0][0] + wps[-1][0]) / 2
        my = (wps[0][1] + wps[-1][1]) / 2 - 4
        A('<text x="%.0f" y="%.0f" font-size="9.5" fill="#64748B" font-style="italic" text-anchor="middle">%s</text>'
          % (mx, my, esc(lbl)))

# ---------- message flows ----------
for fid, wps in mf_edges:
    pts = " ".join("%.0f,%.0f" % p for p in wps)
    A('<polyline points="%s" fill="none" stroke="#0F766E" stroke-width="1.2" '
      'stroke-dasharray="6 4" marker-end="url(#arrm)"/>' % pts)

# ---------- nodes ----------
STYLE = {
    "userTask": ("#E0F2FE", "#0284C7"),
    "serviceTask": ("#DCFCE7", "#16A34A"),
    "sendTask": ("#FEF3C7", "#D97706"),
    "subProcess": ("#EDE9FE", "#7C3AED"),
}
for nid, (x, y, w, h, exp) in shapes.items():
    kind, name = info.get(nid, ("", ""))
    if kind in ("participant", "lane", ""):
        continue
    if kind == "subProcess":
        ise = nid in evsub
        fill, stroke = ("#FCE7F3", "#DB2777") if ise else STYLE["subProcess"]
        dash = ' stroke-dasharray="6 4"' if ise else ""
        A('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" rx="8" fill="%s" stroke="%s" '
          'stroke-width="1.6"%s/>' % (x, y, w, h, fill, stroke, dash))
        A('<rect x="%.0f" y="%.0f" width="%.0f" height="16" rx="8" fill="%s" fill-opacity="0.25"/>'
          % (x, y, w, stroke))
        if exp == "false":
            # BPMN collapsed subprocess marker; the internal diagram is stored
            # on its own BPMNPlane and can be opened from the Modeler overlay.
            cx, by = x + w / 2, y + h - 1
            A('<rect x="%.1f" y="%.1f" width="16" height="16" fill="#FFFFFF" stroke="%s" stroke-width="1.2"/>'
              % (cx - 8, by - 8, stroke))
            A('<path d="M%.1f %.1f H%.1f M%.1f %.1f V%.1f" fill="none" stroke="%s" stroke-width="1.4"/>'
              % (cx - 4, by, cx + 4, cx, by - 4, by + 4, stroke))
        draw_label(x, y + 20, w, name, 11)
        continue
    if kind == "task":
        A('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" rx="8" fill="#F1F5F9" stroke="#64748B" stroke-width="1.3"/>'
          % (x, y, w, h))
        draw_label(x, y + 16, w, name, 9.5)
        continue
    if kind.endswith("Task"):
        fill, stroke = STYLE[kind]
        A('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" rx="8" fill="%s" stroke="%s" stroke-width="1.4"/>'
          % (x, y, w, h, fill, stroke))
        ic = {"userTask": "user", "serviceTask": "gear", "sendTask": "send"}[kind]
        A('<rect x="%.0f" y="%.0f" width="12" height="9" fill="none" stroke="%s" stroke-width="1.1"/>'
          % (x + 5, y + 6, stroke))
        draw_label(x, y + 16, w, name)
        continue
    if kind.endswith("Gateway"):
        cx, cy = x + w / 2, y + h / 2
        A('<polygon points="%.0f,%.0f %.0f,%.0f %.0f,%.0f %.0f,%.0f" fill="#FFFBEB" stroke="#B45309" stroke-width="1.4"/>'
          % (cx, y, x + w, cy, cx, y + h, x, cy))
        if kind == "exclusiveGateway":
            A('<path d="M%.0f %.0f L%.0f %.0f M%.0f %.0f L%.0f %.0f" stroke="#B45309" stroke-width="1.8"/>'
              % (cx - 8, cy - 8, cx + 8, cy + 8, cx - 8, cy + 8, cx + 8, cy - 8))
        elif kind == "eventBasedGateway":
            A('<circle cx="%.0f" cy="%.0f" r="9" fill="none" stroke="#B45309" stroke-width="1.4"/>'
              % (cx, cy))
            A('<path d="M%.0f %.0f l5 -3 l5 3 l-5 3 z" fill="#B45309"/>' % (cx - 5, cy - 1))
        draw_label(x, y + h + 2, w + 60, name, 10)
        continue
    # events
    cx, cy = x + w / 2, y + h / 2
    if kind == "startEvent":
        ise = nid in evsub
        A('<circle cx="%.0f" cy="%.0f" r="16" fill="#ECFDF5" stroke="#059669" stroke-width="%s"%s/>'
          % (cx, cy, "2.2" if ise else "1.6", ' stroke-dasharray="4 3"' if ise else ""))
        A('<path d="M%.0f %.0f l6 -4 l6 4 l-6 4 z" fill="none" stroke="#059669" stroke-width="1.2"/>'
          % (cx - 6, cy - 1))
    elif kind == "endEvent":
        A('<circle cx="%.0f" cy="%.0f" r="16" fill="#FEF2F2" stroke="#B91C1C" stroke-width="3.5"/>' % (cx, cy))
    elif kind == "boundaryEvent":
        A('<circle cx="%.0f" cy="%.0f" r="15" fill="#FEF9C3" stroke="#CA8A04" stroke-width="1.6" '
          'stroke-dasharray="4 3"/>' % (cx, cy))
        A('<circle cx="%.0f" cy="%.0f" r="8" fill="none" stroke="#CA8A04" stroke-width="1.2"/>' % (cx, cy))
        A('<path d="M%.0f %.0f L%.0f %.0f M%.0f %.0f L%.0f %.0f" stroke="#CA8A04" stroke-width="1.2"/>'
          % (cx, cy, cx, cy - 5, cx, cy, cx + 4, cy))
    elif kind == "intermediateThrowEvent":
        A('<circle cx="%.0f" cy="%.0f" r="16" fill="#FEF3C7" stroke="#B45309" stroke-width="2"/>' % (cx, cy))
        A('<path d="M%.0f %.0f l6 -4 l6 4 l-6 4 z" fill="#B45309" stroke="#B45309" stroke-width="1.2"/>'
          % (cx - 6, cy - 1))
    else:  # intermediate catch
        A('<circle cx="%.0f" cy="%.0f" r="16" fill="#EFF6FF" stroke="#2563EB" stroke-width="1.6"/>' % (cx, cy))
        A('<circle cx="%.0f" cy="%.0f" r="12" fill="none" stroke="#2563EB" stroke-width="1.2"/>' % (cx, cy))
        A('<path d="M%.0f %.0f l6 -4 l6 4 l-6 4 z" fill="none" stroke="#2563EB" stroke-width="1.2"/>'
          % (cx - 6, cy - 1))
    draw_label(x - 30, y + h + 4, w + 60, name, 10)

A('</svg>')
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(O))
print("written", OUT, "size", W, "x", H)

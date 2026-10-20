"""Build a compact, one-pool strategic BPMN process from version2 evidence."""

import hashlib
import json
import pathlib
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODEL = ROOT / 'goalModel.txt'
OPERATIONAL = ROOT / 'hospital-patient-pathway.bpmn'
OUTPUT = ROOT / 'strategic-model.bpmn'

BPMN = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
BPMNDI = 'http://www.omg.org/spec/BPMN/20100524/DI'
DC = 'http://www.omg.org/spec/DD/20100524/DC'
DI = 'http://www.omg.org/spec/DD/20100524/DI'

for prefix, uri in [('bpmn', BPMN), ('bpmndi', BPMNDI), ('dc', DC), ('di', DI)]:
    ET.register_namespace(prefix, uri)


def q(namespace, name):
    return f'{{{namespace}}}{name}'


def add(parent, namespace, name, attrs=None, text=None):
    element = ET.SubElement(parent, q(namespace, name), attrs or {})
    if text is not None:
        element.text = str(text)
    return element


def document(parent, text):
    if text:
        add(parent, BPMN, 'documentation', text=text)


def bounds(parent, x, y, width, height):
    add(parent, DC, 'Bounds', {
        'x': str(round(x, 1)), 'y': str(round(y, 1)),
        'width': str(round(width, 1)), 'height': str(round(height, 1)),
    })


def shape(plane, element_id, x, y, width, height, expanded=None):
    attrs = {'id': f'{element_id}_di', 'bpmnElement': element_id}
    if expanded is not None:
        attrs['isExpanded'] = str(expanded).lower()
    node = add(plane, BPMNDI, 'BPMNShape', attrs)
    bounds(node, x, y, width, height)
    return node


def edge(plane, element_id, points, label_box=None):
    node = add(plane, BPMNDI, 'BPMNEdge', {
        'id': f'{element_id}_di', 'bpmnElement': element_id,
    })
    for x, y in points:
        add(node, DI, 'waypoint', {'x': str(round(x, 1)), 'y': str(round(y, 1))})
    if label_box:
        label = add(node, BPMNDI, 'BPMNLabel')
        bounds(label, *label_box)


def add_note(process, plane, note_id, text, x, y, width, height, provenance):
    note = add(process, BPMN, 'textAnnotation', {
        'id': note_id, 'textFormat': 'text/plain',
    })
    add(note, BPMN, 'text', text=text)
    document(note, provenance)
    shape(plane, note_id, x, y, width, height)
    return note


def add_task(parent, task_id, name, source_ids, role, task_type='userTask'):
    task = add(parent, BPMN, task_type, {'id': task_id, 'name': name})
    document(task, f'Accountable role: {role}\nSource BPMN IDs: {", ".join(source_ids)}')
    return task


def add_flow(parent, flow_id, source_id, target_id, flow_nodes, name=None):
    attrs = {'id': flow_id, 'sourceRef': source_id, 'targetRef': target_id}
    if name:
        attrs['name'] = name
    add(parent, BPMN, 'sequenceFlow', attrs)
    add(flow_nodes[source_id], BPMN, 'outgoing', text=flow_id)
    add(flow_nodes[target_id], BPMN, 'incoming', text=flow_id)


def add_association(process, plane, association_id, source_id, target_id,
                    source_box, target_box, direction='None'):
    add(process, BPMN, 'association', {
        'id': association_id,
        'sourceRef': source_id,
        'targetRef': target_id,
        'associationDirection': direction,
    })
    sx, sy, sw, sh = source_box
    tx, ty, tw, th = target_box
    if abs((sx + sw / 2) - (tx + tw / 2)) < 80:
        start = (sx + sw / 2, sy + sh if sy < ty else sy)
        end = (tx + tw / 2, ty if sy < ty else ty + th)
        mid_y = (start[1] + end[1]) / 2
        points = [start, (start[0], mid_y), (end[0], mid_y), end]
    else:
        start = (sx + sw if sx < tx else sx, sy + sh / 2)
        end = (tx if sx < tx else tx + tw, ty + th / 2)
        mid_x = (start[0] + end[0]) / 2
        points = [start, (mid_x, start[1]), (mid_x, end[1]), end]
    edge(plane, association_id, points)


model = json.loads(MODEL.read_text(encoding='utf-8'))
operational_root = ET.parse(OPERATIONAL).getroot()
operational_ids = {element.attrib['id'] for element in operational_root.iter()
                   if 'id' in element.attrib}

# The main view uses only evidence-backed business stages on the desired path.
stages = [
    {'id': 'SM_Start_Referral', 'kind': 'startEvent', 'name': 'Referral received',
     'lane': 'Lane_Secretaries', 'source': ['Top_Catch_Referral']},
    {'id': 'SM_Check_Referral', 'kind': 'userTask',
     'name': 'Check referral and prepare clinical review',
     'lane': 'Lane_Secretaries', 'source': ['SP01_CheckDocuments']},
    {'id': 'SM_Review_Referral', 'kind': 'userTask',
     'name': 'Review referral and accept case',
     'lane': 'Lane_Consultants', 'source': ['SP02_ClinicalReview']},
    {'id': 'SM_Assess_Patient', 'kind': 'userTask',
     'name': 'Assess patient and agree treatment plan',
     'lane': 'Lane_Consultants', 'source': ['SP04_Assess', 'SP04_RecordConsent', 'SP04_CreateRequest']},
    {'id': 'SM_Confirm_Resources', 'kind': 'userTask',
     'name': 'Confirm treatment resources',
     'lane': 'Lane_TreatmentBookings', 'source': ['SP05_BookInternal', 'SP05_RequestAvailability']},
    {'id': 'SM_Confirm_Funding', 'kind': 'subProcess',
     'name': 'Confirm funding and payment readiness',
     'lane': 'Lane_Finance', 'source': ['SP06_Determine', 'SP07_CalculateCharge',
                                      'SP07_SendPaymentRequest', 'SP07_RecordTransaction',
                                      'SP07_ConfirmBooking']},
    {'id': 'SM_Arrange_Appointment', 'kind': 'userTask',
     'name': 'Arrange appointment and notify patient',
     'lane': 'Lane_OutpatientBookings', 'source': ['SP03_RequestSlot', 'SP03_ConfirmBooking',
                                                  'SP03_SendLetter']},
    {'id': 'SM_Deliver_Treatment', 'kind': 'subProcess',
     'name': 'Deliver treatment and review response',
     'lane': 'Lane_Clinical', 'source': ['SP08_RequestBloods', 'SP08_Review', 'SP08_Administer']},
    {'id': 'SM_Communicate_Outcome', 'kind': 'userTask',
     'name': 'Communicate outcome and follow-up plan',
     'lane': 'Lane_Pathway', 'source': ['SP13_Draft', 'SP13_Approve', 'SP13_Distribute',
                                       'SP13_RecordDates', 'SP14_Inform']},
    {'id': 'SM_End_Outcome', 'kind': 'endEvent', 'name': 'Outcome communicated',
     'lane': 'Lane_Pathway', 'source': ['SP13_Distribute', 'SP14_Inform']},
]
for stage in stages:
    missing = set(stage['source']) - operational_ids
    if missing:
        raise ValueError(f'Strategic stage {stage["id"]} has missing operational IDs: {sorted(missing)}')

definitions = ET.Element(q(BPMN, 'definitions'), {
    'id': 'StrategicModelDefinitions',
    'targetNamespace': 'https://example.org/hpas/strategic-process',
    'exporter': 'HPAS Strategic BPMN generator',
    'exporterVersion': '2.0',
})
model_hash = hashlib.sha256(MODEL.read_bytes()).hexdigest()
operational_hash = hashlib.sha256(OPERATIONAL.read_bytes()).hexdigest()
document(definitions,
         'Compact strategic happy-path model derived from the version2 operational model and i* evidence. '
         'It is a discussion and improvement model and is not an executable deployment artifact. '
         f'i* source SHA-256: {model_hash}. Operational BPMN SHA-256: {operational_hash}.')
collaboration = add(definitions, BPMN, 'collaboration', {'id': 'SM_Collaboration'})
process = add(definitions, BPMN, 'process', {
    'id': 'SM_Process_HospitalPathway',
    'name': 'Hospital Patient Pathway - Strategic Process',
    'isExecutable': 'false',
})
document(process,
         'Single-pool strategic process. It shows the desired route from referral to communicated outcome. '
         'Operational exceptions, technical connector steps and repeated treatment cycles are omitted from '
         'the main view or grouped within collapsed subprocesses.')
participant = add(collaboration, BPMN, 'participant', {
    'id': 'SM_Participant_HospitalPathway',
    'name': 'Hospital Patient Pathway (customer: Patient)',
    'processRef': process.attrib['id'],
})

lane_specs = [
    ('Lane_Secretaries', 'Medical Secretaries'),
    ('Lane_Consultants', 'Consultants'),
    ('Lane_TreatmentBookings', 'Treatment Bookings'),
    ('Lane_Finance', 'Finance Team'),
    ('Lane_OutpatientBookings', 'Outpatient Bookings'),
    ('Lane_Clinical', 'Clinical Team'),
    ('Lane_Pathway', 'Pathway Coordination'),
]
lane_set = add(process, BPMN, 'laneSet', {'id': 'SM_LaneSet_HospitalPathway'})
lanes = {}
for lane_id, name in lane_specs:
    lanes[lane_id] = add(lane_set, BPMN, 'lane', {'id': lane_id, 'name': name})

flow_nodes = {}
node_boxes = {}
subprocesses = {}
lane_y = {lane_id: 140 + index * 156 for index, (lane_id, _) in enumerate(lane_specs)}
stage_x = {
    'SM_Start_Referral': 250,
    'SM_Check_Referral': 360,
    'SM_Review_Referral': 590,
    'SM_Assess_Patient': 830,
    'SM_Confirm_Resources': 1070,
    'SM_Confirm_Funding': 1310,
    'SM_Arrange_Appointment': 1550,
    'SM_Deliver_Treatment': 1790,
    'SM_Communicate_Outcome': 2030,
    'SM_End_Outcome': 2270,
}

for stage in stages:
    stage_id = stage['id']
    lane_top = lane_y[stage['lane']]
    if stage['kind'] in ('startEvent', 'endEvent'):
        element = add(process, BPMN, stage['kind'], {'id': stage_id, 'name': stage['name']})
        box = (stage_x[stage_id], lane_top + 60, 36, 36)
    elif stage['kind'] == 'subProcess':
        element = add(process, BPMN, 'subProcess', {'id': stage_id, 'name': stage['name']})
        document(element, f'Grouped strategic activities. Source BPMN IDs: {", ".join(stage["source"])}')
        subprocesses[stage_id] = element
        box = (stage_x[stage_id], lane_top + 42, 205, 72)
    else:
        element = add_task(process, stage_id, stage['name'], stage['source'],
                           dict(lane_specs)[stage['lane']])
        box = (stage_x[stage_id], lane_top + 46, 190, 64)
    flow_nodes[stage_id] = element
    node_boxes[stage_id] = box
    add(lanes[stage['lane']], BPMN, 'flowNodeRef', text=stage_id)

for index, (source, target) in enumerate(zip(stages, stages[1:]), 1):
    add_flow(process, f'SM_Flow_{index:02d}', source['id'], target['id'], flow_nodes)

# The two collapsed subprocesses retain a small, inspectable business-level view.
subprocess_details = {
    'SM_Confirm_Funding': [
        ('Confirm available treatment resource', ['SP05_BookInternal', 'SP05_RequestAvailability']),
        ('Determine funding and applicable charge', ['SP06_Determine', 'SP07_CalculateCharge']),
        ('Confirm payment and release booking', ['SP07_SendPaymentRequest', 'SP07_RecordTransaction',
                                                 'SP07_ConfirmBooking']),
    ],
    'SM_Deliver_Treatment': [
        ('Confirm treatment readiness', ['SP08_RequestBloods', 'SP08_Review']),
        ('Deliver scheduled treatment', ['SP08_Administer']),
        ('Review response and next step', ['SP08_Review', 'SP08_GW_More']),
    ],
}
nested_diagrams = []
for subprocess_id, steps in subprocess_details.items():
    subprocess = subprocesses[subprocess_id]
    nested_nodes = {}
    nested_boxes = {}
    start_id = f'{subprocess_id}_Start'
    start = add(subprocess, BPMN, 'startEvent', {'id': start_id, 'name': 'Work begins'})
    nested_nodes[start_id] = start
    nested_boxes[start_id] = (100, 170, 36, 36)
    prior_id = start_id
    for index, (name, source_ids) in enumerate(steps, 1):
        task_id = f'{subprocess_id}_Task_{index}'
        task = add_task(subprocess, task_id, name, source_ids,
                        'Finance Team and Treatment Bookings' if subprocess_id == 'SM_Confirm_Funding'
                        else 'Clinical Team')
        nested_nodes[task_id] = task
        nested_boxes[task_id] = (260 + (index - 1) * 300, 155, 235, 66)
        prior_id = task_id
    end_id = f'{subprocess_id}_End'
    end = add(subprocess, BPMN, 'endEvent', {'id': end_id, 'name': 'Work complete'})
    nested_nodes[end_id] = end
    nested_boxes[end_id] = (260 + len(steps) * 300, 170, 36, 36)
    order = [start_id, *(f'{subprocess_id}_Task_{i}' for i in range(1, len(steps) + 1)), end_id]
    for index, (source_id, target_id) in enumerate(zip(order, order[1:]), 1):
        add_flow(subprocess, f'{subprocess_id}_Flow_{index:02d}',
                 source_id, target_id, nested_nodes)
    nested_diagram = ET.Element(q(BPMNDI, 'BPMNDiagram'), {
        'id': f'{subprocess_id}_Diagram', 'name': f'{subprocess.attrib["name"]} details',
    })
    nested_plane = add(nested_diagram, BPMNDI, 'BPMNPlane', {
        'id': f'{subprocess_id}_Plane', 'bpmnElement': subprocess_id,
    })
    for element_id, (x, y, width, height) in nested_boxes.items():
        shape(nested_plane, element_id, x, y, width, height)
    for index, (source_id, target_id) in enumerate(zip(order, order[1:]), 1):
        sx, sy, sw, sh = nested_boxes[source_id]
        tx, ty, tw, th = nested_boxes[target_id]
        mid_x = (sx + sw + tx) / 2
        edge(nested_plane, f'{subprocess_id}_Flow_{index:02d}',
             [(sx + sw, sy + sh / 2), (mid_x, sy + sh / 2),
              (mid_x, ty + th / 2), (tx, ty + th / 2)])
    nested_diagrams.append(nested_diagram)

# Main BPMN page: one pool, seven responsibility lanes, and ten flow objects.
diagram = add(definitions, BPMNDI, 'BPMNDiagram', {
    'id': 'SM_Main_Diagram', 'name': 'Strategic happy path',
})
plane = add(diagram, BPMNDI, 'BPMNPlane', {
    'id': 'SM_Main_Plane', 'bpmnElement': 'SM_Collaboration',
})
pool = (100, 140, 2500, len(lane_specs) * 156)
shape(plane, participant.attrib['id'], *pool)
for index, (lane_id, _) in enumerate(lane_specs):
    shape(plane, lane_id, 330, 140 + index * 156, 2270, 156)

for stage in stages:
    box = node_boxes[stage['id']]
    shape(plane, stage['id'], *box,
          expanded=False if stage['kind'] == 'subProcess' else None)

# Four key information objects keep the model useful without adding operational detail.
data_specs = [
    ('SM_Data_Referral', 'Referral and supporting information', 'SM_Check_Referral'),
    ('SM_Data_TreatmentPlan', 'Agreed treatment plan', 'SM_Assess_Patient'),
    ('SM_Data_Readiness', 'Funding and booking readiness', 'SM_Confirm_Funding'),
    ('SM_Data_Outcome', 'Treatment outcome and follow-up plan', 'SM_Deliver_Treatment'),
]
data_boxes = {}
for index, (data_id, name, activity_id) in enumerate(data_specs):
    data = add(process, BPMN, 'dataObject', {'id': f'{data_id}_Object', 'name': name})
    reference = add(process, BPMN, 'dataObjectReference', {
        'id': data_id, 'name': name, 'dataObjectRef': data.attrib['id'],
    })
    x, y, width, height = node_boxes[activity_id]
    data_x = x + 12
    data_y = y + height + 20
    data_boxes[data_id] = (data_x, data_y, 170, 34)
    shape(plane, data_id, *data_boxes[data_id])

customer_note = add_note(
    process, plane, 'SM_Note_Customer',
    'Customer: Patient. The referring organisation initiates the pathway.',
    120, 35, 540, 52,
    'Customer identity and referral trigger are drawn from the operational collaboration.',
)
kpi_note = add_note(
    process, plane, 'SM_Note_KPI',
    'Candidate KPI: elapsed time from referral receipt to outcome communication. Target not set in source evidence.',
    1510, 35, 1050, 52,
    'The course recommends KPIs with explicit characteristics. No target value was supplied, so none is invented.',
)
note_boxes = {
    'SM_Note_Customer': (120, 35, 540, 52),
    'SM_Note_KPI': (1510, 35, 1050, 52),
}

# Data associations show the strategic hand-offs between major stages.
data_links = [
    ('SM_Data_Referral', None, 'SM_Check_Referral'),
    ('SM_Data_TreatmentPlan', 'SM_Assess_Patient', 'SM_Confirm_Resources'),
    ('SM_Data_Readiness', 'SM_Confirm_Funding', 'SM_Arrange_Appointment'),
    ('SM_Data_Outcome', 'SM_Deliver_Treatment', 'SM_Communicate_Outcome'),
]
for index, (data_id, producer_id, consumer_id) in enumerate(data_links, 1):
    if producer_id is None:
        source_id, target_id = data_id, consumer_id
        source_box, target_box = data_boxes[data_id], node_boxes[consumer_id]
    else:
        source_id, target_id = producer_id, data_id
        source_box, target_box = node_boxes[producer_id], data_boxes[data_id]
    add_association(process, plane, f'SM_Assoc_Data_{index:02d}',
                    source_id, target_id, source_box, target_box, direction='One')
    if producer_id is not None:
        add_association(process, plane, f'SM_Assoc_Data_{index:02d}_Input',
                        data_id, consumer_id, data_boxes[data_id], node_boxes[consumer_id],
                        direction='One')

add_association(process, plane, 'SM_Assoc_Customer', 'SM_Note_Customer',
                'SM_Start_Referral', note_boxes['SM_Note_Customer'], node_boxes['SM_Start_Referral'])

# Main sequence flow routes are orthogonal and follow the desired path left to right.
for index, (source, target) in enumerate(zip(stages, stages[1:]), 1):
    source_box = node_boxes[source['id']]
    target_box = node_boxes[target['id']]
    sx, sy, sw, sh = source_box
    tx, ty, tw, th = target_box
    source_mid, target_mid = sy + sh / 2, ty + th / 2
    bend_x = (sx + sw + tx) / 2
    edge(plane, f'SM_Flow_{index:02d}',
         [(sx + sw, source_mid), (bend_x, source_mid),
          (bend_x, target_mid), (tx, target_mid)])

for nested_diagram in nested_diagrams:
    definitions.append(nested_diagram)

tree = ET.ElementTree(definitions)
ET.indent(tree, space='  ')
tree.write(OUTPUT, encoding='utf-8', xml_declaration=True)

print(json.dumps({
    'output': str(OUTPUT),
    'pools': 1,
    'lanes': len(lane_specs),
    'mainFlowObjects': len(stages),
    'mainArtifacts': len(data_specs) + 2,
    'collapsedSubprocesses': len(subprocesses),
    'mainGateways': 0,
    'sourceIdsValidated': True,
    'executable': False,
}, ensure_ascii=False))

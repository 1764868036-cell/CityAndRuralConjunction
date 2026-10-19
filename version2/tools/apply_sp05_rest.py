"""Apply the SP05 HTTP request/response adaptation after model generation."""
from pathlib import Path
import re
from lxml import etree as ET

ROOT = Path(__file__).resolve().parent.parent
B = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
Z = 'http://camunda.org/schema/zeebe/1.0'
N = {'b': B, 'z': Z}


def apply(path=ROOT / 'hospital-patient-pathway.bpmn'):
    tree = ET.parse(str(path))
    root = tree.getroot()
    task = root.xpath('//b:*[@id="SP05_RequestAvailability"]', namespaces=N)[0]
    task.tag = f'{{{B}}}serviceTask'
    ext = task.find(f'{{{B}}}extensionElements')
    for child in list(ext):
        ext.remove(child)
    ET.SubElement(ext, f'{{{Z}}}taskDefinition', type='io.camunda:http-json:1', retries='3')
    props = ET.SubElement(ext, f'{{{Z}}}taskHeaders')
    ET.SubElement(props, f'{{{Z}}}header', key='retryBackoff', value='PT30S')
    ET.SubElement(props, f'{{{Z}}}header', key='resultExpression',
                  value='= {externalAvailable: if response.body.externalAvailable instance of boolean then response.body.externalAvailable else null}')
    mapping = ET.SubElement(ext, f'{{{Z}}}ioMapping')
    for target, source in {
        'method': 'GET',
        'url': '{{secrets.SP05_AVAILABILITY_URL}}',
        'authentication.type': 'noAuth',
        'queryParameters': '= {referralId: referralId, treatmentPlanId: treatmentPlanId}',
        'headers': '= if sp05MockExample instance of string then {"x-mock-response-name": sp05MockExample} else {}',
        'connectionTimeoutInSeconds': '= 10',
        'readTimeoutInSeconds': '= 20',
    }.items():
        ET.SubElement(mapping, f'{{{Z}}}input', source=source, target=target)
    obsolete = {'SP05_Catch_Availability', 'Flow_088',
                'MF_SP05_RequestAvailability_XExt_AvailStart',
                'MF_XExt_SendAvail_SP05_Catch_Availability'}
    for el in list(root.iter()):
        if (el.get('id') in obsolete or el.get('bpmnElement') in obsolete
                or (ET.QName(el).localname in {'incoming', 'flowNodeRef'} and el.text in obsolete)):
            el.getparent().remove(el)
    flow = root.xpath('//b:sequenceFlow[@id="Flow_087"]', namespaces=N)[0]
    flow.set('targetRef', 'SP05_GW_Available')
    gateway = root.xpath('//b:exclusiveGateway[@id="SP05_GW_Available"]', namespaces=N)[0]
    if not any(c.text == 'Flow_087' for c in gateway.findall(f'{{{B}}}incoming')):
        ET.SubElement(gateway, f'{{{B}}}incoming').text = 'Flow_087'
        # BPMN schema requires incoming before outgoing.
        gateway.insert(0, gateway[-1])
    dn = {'d': 'http://www.omg.org/spec/BPMN/20100524/DI',
          'i': 'http://www.omg.org/spec/DD/20100524/DI'}
    edge = root.xpath('//d:BPMNEdge[@bpmnElement="Flow_087"]', namespaces=dn)[0]
    old = root.xpath('//d:BPMNShape[@bpmnElement="SP05_GW_Available"]/*', namespaces=dn)[0]
    points = edge.findall('i:waypoint', dn)
    points[-1].set('x', old.get('x'))
    points[-1].set('y', str(float(old.get('y')) + float(old.get('height')) / 2))
    # The rollback model contains empty diagram shells rejected by the BPMN XSD.
    for diagram in root.xpath('//d:BPMNDiagram[not(d:BPMNPlane)]', namespaces=dn):
        diagram.getparent().remove(diagram)
    xml = ET.tostring(tree, encoding='unicode', pretty_print=True)
    xml = re.sub(r'(?<!\s)/>', ' />', xml)
    path.write_text('<?xml version="1.0" encoding="UTF-8"?>\n' + xml, encoding='utf-8')


if __name__ == '__main__':
    apply()
    print('SP05 REST Connector applied')

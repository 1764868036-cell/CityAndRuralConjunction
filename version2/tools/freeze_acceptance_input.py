"""Freeze the model/forms used by the acceptance suite when the main model is being edited."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from lxml import etree as ET

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--normalize-empty-di', action='store_true',
                    help='Remove empty BPMNDiagram elements in the test snapshot only; record every removal.')
args = parser.parse_args()
dest = ROOT / 'evidence/acceptance-input'
(dest / 'forms').mkdir(parents=True, exist_ok=True)
raw = (ROOT / 'hospital-patient-pathway.bpmn').read_bytes()
(dest / 'source.bpmn.keep').write_bytes(raw)
model = ET.fromstring(raw)
removed = []
if args.normalize_empty_di:
    for diagram in model.xpath('//d:BPMNDiagram[not(d:BPMNPlane)]', namespaces={
            'd': 'http://www.omg.org/spec/BPMN/20100524/DI'}):
        removed.append(diagram.get('id'))
        diagram.getparent().remove(diagram)
frozen = ET.tostring(model, encoding='utf-8', xml_declaration=True, pretty_print=True)
(dest / 'hospital-patient-pathway.bpmn.keep').write_bytes(frozen)
forms = {}
for old in (dest / 'forms').glob('*.form.keep'):
    old.unlink()
for form in sorted((ROOT / 'forms').glob('*.form')):
    data = form.read_bytes()
    (dest / 'forms' / (form.name + '.keep')).write_bytes(data)
    forms[form.name] = hashlib.sha256(data).hexdigest()
manifest = dict(capturedAt=datetime.now(timezone.utc).isoformat(),
                sourceModel='hospital-patient-pathway.bpmn', sourceSHA256=hashlib.sha256(raw).hexdigest(),
                testedSHA256=hashlib.sha256(frozen).hexdigest(),
                normalizations={'removedEmptyDiagramIds': removed}, forms=forms)
(dest / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in manifest.items() if k != 'forms'}, indent=2))

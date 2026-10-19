import json, sys, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / 'evidence/full-e2e-state.json'
def api(path, data=None):
    req = urllib.request.Request('http://localhost:8080/v2/' + path, data=json.dumps(data).encode() if data is not None else None, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as response:
        body = response.read()
        return json.loads(body) if body else {}
s = json.loads(STATE.read_text()) if STATE.exists() else {}
cmd = sys.argv[1]
if cmd == 'start':
    name = sys.argv[2]
    v = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
    v.update(referralId='TEST-FULL-' + name, patientId='TEST-PATIENT-' + name)
    definition = v.pop('_processDefinitionId', 'Process_HPAS')
    res = api('process-instances', {'processDefinitionId': definition, 'variables': v})
    s[name] = {'key': res['processInstanceKey'], 'referralId': v['referralId']}
    STATE.write_text(json.dumps(s))
    print(json.dumps(s[name]))
elif cmd == 'msg':
    name, msg = sys.argv[2:4]
    v = json.loads(sys.argv[4]) if len(sys.argv) > 4 else {}
    print(json.dumps(api('messages/publication', {'name': msg, 'correlationKey': s[name]['referralId'], 'timeToLive': 600000, 'variables': v})))
elif cmd == 'tasks':
    tasks = []
    for name, pi in s.items():
        res = api('user-tasks/search', {'filter': {'processInstanceKey': pi['key'], 'state': 'CREATED'}, 'page': {'limit': 100}})
        for task in res['items']:
            if task.get('assignee') != 'demo':
                api('user-tasks/' + task['userTaskKey'] + '/assignment', {'assignee': 'demo', 'allowOverride': True})
        tasks.extend({'scenario': name, 'task': x['elementId'], 'key': x['userTaskKey']} for x in res['items'])
    (ROOT / 'evidence/pending-ui-tasks.json').write_text(json.dumps(tasks))
    print(json.dumps(tasks))
elif cmd == 'status':
    for name, pi in s.items():
        res = api('process-instances/' + pi['key'])
        print(name, res['state'], res.get('hasIncident'))
elif cmd == 'coverage':
    from lxml import etree as ET
    model = ET.parse(str(ROOT / 'hospital-patient-pathway.bpmn'))
    ns = {'b': 'http://www.omg.org/spec/BPMN/20100524/MODEL',
          'z': 'http://camunda.org/schema/zeebe/1.0'}
    expected = {task.get('id') for task in model.xpath('//b:userTask', namespaces=ns)}
    completed = set()
    rows = {}
    for name, pi in s.items():
        res = api('user-tasks/search', {'filter': {'processInstanceKey': pi['key'], 'state': 'COMPLETED'}, 'page': {'limit': 100}})
        ids = sorted({x['elementId'] for x in res.get('items', [])})
        rows[name] = ids
        completed.update(ids)
    coverage = {'expectedCount': len(expected), 'completedUniqueCount': len(completed & expected),
                'missing': sorted(expected - completed), 'byScenario': rows}
    (ROOT / 'evidence/form-coverage.json').write_text(json.dumps(coverage, indent=2))
    print(json.dumps(coverage, indent=2))
elif cmd == 'cleanup':
    import subprocess
    for name, pi in s.items():
        res = api('process-instances/' + pi['key'])
        if res['state'] == 'ACTIVE':
            command = f"c8ctl cancel pi {pi['key']} --profile=local"
            result = subprocess.run(['pwsh', '-NoProfile', '-Command', command],
                                    text=True, capture_output=True)
            print(name, 'cancelled' if result.returncode == 0 else 'FAILED', result.stdout.strip(), result.stderr.strip())

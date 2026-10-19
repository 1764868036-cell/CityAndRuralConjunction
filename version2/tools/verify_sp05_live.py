"""Live SP05 verification on the local teaching cluster, using real workers/HTTP.

Human tasks are completed via API. Only synthetic test instances are touched.
"""
import hashlib
import json
import time
import urllib.request
import urllib.error
import argparse
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = 'http://localhost:8080/v2'
RECOVERED = '2251799813701827'
DEFINITION = '2251799813701644'


def api(path, body=None):
    req = urllib.request.Request(BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type': 'application/json'})
    for attempt in range(12):
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            # Read API indexing is eventually consistent after instance creation.
            if body is None and error.code == 404 and attempt < 11:
                time.sleep(0.5)
                continue
            raise


def search(resource, key):
    return api('/' + resource + '/search', {
        'filter': {'processInstanceKey': key}, 'page': {'limit': 200}})['items']


def snapshot(key):
    return {'instance': api('/process-instances/' + key),
            'elements': search('element-instances', key),
            'variables': search('variables', key),
            'incidents': search('incidents', key)}


def finish(key):
    # Finish only known synthetic test tasks, including the parallel letter branch.
    values = {'SP08_Review': {'fitnessDecision': 'fit', 'moreCycles': False},
              'SP13_Draft': {'clinicalContent': 'Synthetic E2E letter'},
              'SP13_Approve': {'letterApproved': True},
              'SP13_AdminProcess': {'adminCheck': 'ok', 'followUpInstructed': False}}
    closed = False
    for _ in range(35):
        state = api('/process-instances/' + key)
        if state['state'] == 'COMPLETED':
            return
        for task in search('user-tasks', key):
            if task['state'] == 'CREATED' and task['elementId'] in values:
                api('/user-tasks/' + task['userTaskKey'] + '/completion',
                    {'variables': values[task['elementId']]})
                print('completed', key, task['elementId'], flush=True)
        elements = search('element-instances', key)
        if not closed and any(e['elementId'] == 'Top_WaitClosure' and e['state'] == 'ACTIVE' for e in elements):
            variables = {v['name']: json.loads(v['value']) for v in search('variables', key)
                         if v['scopeKey'] == key}
            api('/messages/publication', {'name': 'HPAS: Pathway closure authorised',
                'correlationKey': variables['referralId'], 'timeToLive': 60000})
            closed = True
        time.sleep(1)
    raise RuntimeError('Test instance did not complete: ' + key)


def start(scenario):
    referral = 'E2E-SP05-' + scenario + '-' + str(time.time_ns())
    response = api('/process-instances', {'processDefinitionKey': DEFINITION,
        'startInstructions': [{'elementId': 'SP05_RequestAvailability'}],
        'variables': {'referralId': referral, 'patientId': 'E2E-SYNTHETIC',
            'treatmentPlanId': 'E2E-PLAN-001', 'sp05MockExample': scenario,
            'fundingRoute': 'hospital', 'moreCycles': False}})
    print('created', scenario, response['processInstanceKey'], flush=True)
    return response['processInstanceKey']


def completed(data, element):
    return any(e['elementId'] == element and e['state'] == 'COMPLETED'
               for e in data['elements'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--resume-unavailable')
    args = parser.parse_args()
    report = {'timestampUtc': datetime.now(timezone.utc).isoformat(),
        'sourceSha256': hashlib.sha256((ROOT / 'hospital-patient-pathway.bpmn').read_bytes()).hexdigest(),
        'mockUrl': 'http://localhost:4510/availability', 'mode': 'real local cluster/workers/HTTP; API user task completion',
        'cases': []}
    try:
        finish(RECOVERED)
        data = snapshot(RECOVERED)
        assert completed(data, 'SP05_RequestAvailability') and completed(data, 'SP05_BookExternal')
        assert data['instance']['state'] == 'COMPLETED' and not data['instance']['hasIncident']
        report['cases'].append({'scenario': 'available', 'scope': 'full pathway from start, recovered missing-secret incident',
                                'passed': True, **data})
        print('PASS available: full instance completed', flush=True)
        key = args.resume_unavailable or start('unavailable')
        for _ in range(30):
            data = snapshot(key)
            if completed(data, 'SP05_NotifyPending') and completed(data, 'SP05_BookExternal'):
                break
            time.sleep(1)
        assert completed(data, 'SP05_NotifyPending') and completed(data, 'SP05_Catch_Released')
        assert completed(data, 'SP05_BookExternal') and not data['instance']['hasIncident']
        report['cases'].append({'scenario': 'unavailable', 'scope': 'SP05 segment; real worker auto-simulates slot release', 'passed': True, **data})
        api('/process-instances/' + key + '/cancellation', {})
        report['cases'][-1]['cleanup'] = 'test instance cancelled after evidence capture'
        print('PASS unavailable: pending -> released -> booking', flush=True)
        key = start('upstream-error')
        for _ in range(100):
            data = snapshot(key)
            active = [i for i in data['incidents'] if i['state'] == 'ACTIVE']
            if active:
                break
            time.sleep(1)
        assert active and any('503' in i['errorMessage'] for i in active)
        assert not completed(data, 'SP05_BookExternal')
        report['cases'].append({'scenario': 'upstream-error', 'scope': 'SP05 segment; HTTP 503 produces incident, no booking', 'passed': True, **data})
        api('/process-instances/' + key + '/cancellation', {})
        report['cases'][-1]['cleanup'] = 'test instance cancelled after expected incident capture'
        print('PASS upstream-error: HTTP 503 incident, no booking', flush=True)
        report['passed'] = True
    except Exception as error:
        report['passed'] = False
        report['error'] = repr(error)
        raise
    finally:
        out = ROOT / 'evidence/sp05-e2e-results.json'
        out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
        print('Evidence:', out, flush=True)

from lxml import etree as E
from pathlib import Path
root = Path(__file__).resolve().parent.parent
r = E.parse(str(root / 'hospital-patient-pathway.bpmn'))
n = {'b':'http://www.omg.org/spec/BPMN/20100524/MODEL'}
p = r.xpath('//b:process[@id="Process_HPAS"]',namespaces=n)[0]
p.set('id','Process_HPAS_TimerTest')
p.set('name','HPAS accelerated timer TEST ONLY')
for x in r.xpath('//*[@processRef="Process_HPAS"]'):
    x.set('processRef','Process_HPAS_TimerTest')
r.xpath('//b:boundaryEvent[@id="SP13_Timer7d"]/b:timerEventDefinition/b:timeDuration',namespaces=n)[0].text='PT3S'
(root / 'evidence/fixtures').mkdir(exist_ok=True)
r.write(str(root / 'evidence/fixtures/timer-test.bpmn'),encoding='utf-8',xml_declaration=True)

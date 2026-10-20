"""Verify that subprocess repair left main content and existing shared contracts intact."""
from pathlib import Path
import copy, hashlib, json, re
from lxml import etree as E
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'evidence/subprocess-repair-20261020'
B='{http://www.omg.org/spec/BPMN/20100524/MODEL}'
D='{http://www.omg.org/spec/BPMN/20100524/DI}'
def protected(data):
    tree=E.fromstring(data)
    proc=tree.find(B+'process[@id="Process_HPAS"]')
    for child in list(proc.findall(B+'subProcess')):proc.remove(child)
    for diagram in list(tree.findall(D+'BPMNDiagram')):
        if diagram.find(D+'BPMNPlane').get('bpmnElement')!='Collaboration_HPAS':tree.remove(diagram)
    for msg in list(tree.findall(B+'message')):
        if msg.get('id').startswith('Msg_Sub_'):tree.remove(msg)
    for item in tree.iter():
        if item.text is not None and not item.text.strip():item.text=None
        item.tail=None
    return E.tostring(tree,method='c14n')
def main_worker_cases(data):
    # The contiguous P01-P08 handler section and original shared publishing helpers.
    first=data[data.index('    switch (type) {'):data.index('      case "hpas-sp03b-search-slot"')]
    shared=data[data.index('  private void publish(String name, String referralId'):]
    closure=re.search(r'      case "hpas-notify-pathway-closure".*?;',data,re.S).group()
    return (first+shared+closure).encode()
if __name__=='__main__':
    before=(OUT/'before.bpmn.keep').read_bytes()
    after=(ROOT/'bpmn/hospital-patient-pathway.bpmn').read_bytes()
    assert protected(before)==protected(after),'Main process, overview or existing shared contracts changed'
    wb=(OUT/'HpasJobExecutor.java.keep').read_text(encoding='utf-8')
    wa=(ROOT/'java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java').read_text(encoding='utf-8')
    assert main_worker_cases(wb)==main_worker_cases(wa),'Existing main-flow worker behavior changed'
    baseline=E.fromstring(before);current=E.fromstring(after)
    main=baseline.find(B+'process[@id="Process_HPAS"]')
    currentmain=current.find(B+'process[@id="Process_HPAS"]')
    mainforms={f.get('formId') for n in main if n.tag!=B+'subProcess' for f in n.iter('{http://camunda.org/schema/zeebe/1.0}formDefinition')}
    editedforms={p.name.removesuffix('.form.keep') for p in OUT.glob('*.form.keep')}
    assert not mainforms & editedforms,'A main-flow form was edited'
    counts=lambda p:len(p.findall(B+'sequenceFlow'))
    report={'protectedContentUnchanged':True,'mainWorkerCasesUnchanged':True,'mainFormsExcludedFromRepairSet':True,
        'protectedSha256':hashlib.sha256(protected(before)).hexdigest(),
        'mainSequenceFlowsBefore':counts(main),'mainSequenceFlowsAfter':counts(currentmain),
        'beforeSha256':hashlib.sha256(before).hexdigest(),'afterSha256':hashlib.sha256(after).hexdigest()}
    (OUT/'main-process-preservation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))

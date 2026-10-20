"""Real-engine routing regressions on the isolated 8180 acceptance cluster.

Deploys a renamed test definition; completes synthetic tasks via API and controls
jobs explicitly. Worker business rules are tested separately in SubprocessWorkerTest.
Never targets the 8080 interactive cluster or cancels non-test instances.
"""
from pathlib import Path
import json, time, uuid, hashlib
import requests

ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:8180/v2'
RUN=uuid.uuid4().hex[:10]
CREATED=[];RESULTS=[]
def api(path,data=None,method='POST'):
    res=requests.request(method,BASE+path,json=data,timeout=25)
    if not res.ok:raise RuntimeError(f'{res.status_code} {path}: {res.text[:2500]}')
    return res.json() if res.content else {}
def search(resource,key):
    return api('/'+resource+'/search',{'filter':{'processInstanceKey':key},'page':{'limit':100}})['items']
def wait_for(fn,label):
    end=time.monotonic()+12
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(.25)
    raise AssertionError('Timed out: '+label)
def task(key,id,data):
    row=wait_for(lambda:next((t for t in search('user-tasks',key) if t['elementId']==id and t['state']=='CREATED'),None),id)
    api('/user-tasks/'+row['userTaskKey']+'/completion',{'variables':data})
def active(key,id):
    return any(e['elementId']==id and e['state']=='ACTIVE' for e in search('element-instances',key))
def done(key,id):
    return any(e['elementId']==id and e['state']=='COMPLETED' for e in search('element-instances',key))
def message(name,key,data=None):
    api('/messages/publication',{'name':'HPAS: '+name,'correlationKey':key,'messageId':str(uuid.uuid4()),'timeToLive':60000,'variables':data or {}})
def start():
    referral='TEST-SUB-'+RUN+'-'+str(len(CREATED))
    row=api('/process-instances',{'processDefinitionKey':DEFINITION,'variables':{'referralId':referral,'patientId':'TEST-SYNTHETIC','referralReceived':False}})
    key=row['processInstanceKey'];CREATED.append(key)
    wait_for(lambda:active(key,'Top_Catch_Referral'),'root ready')
    return key,referral
def work(key,type,data=None):
    jobs=wait_for(lambda:api('/jobs/activation',{'type':'TEST-SUB-'+RUN+':'+type,'timeout':10000,'maxJobsToActivate':100,'requestTimeout':-1,'worker':'subprocess-repair-'+RUN}).get('jobs'),type)
    owned=[j for j in jobs if j['processInstanceKey']==key]
    assert owned,('No matching job',key,type)
    for job in owned:api('/jobs/'+job['jobKey']+'/completion',{'variables':data or {}})
def case(name,fn):
    fn();RESULTS.append({'name':name,'passed':True});print('PASS',name,flush=True)
def finance_refusal():
    k,ref=start();message('Treatment change requested',ref)
    task(k,'SP09_Assess',{'urgentChange':False,'formallyAuthorised':True,'financialImpact':True,'authorisingClinician':'TEST','changeReason':'TEST'})
    work(k,'hpas-sp09-validate-formal',{'formallyAuthorised':True})
    task(k,'SP09_FinanceReview',{'financialReviewApproved':False,'refundRequired':False})
    wait_for(lambda:done(k,'SP09_End_Rejected'),'finance rejection');assert not done(k,'SP09_ApplyChange')
def finance_approval():
    k,ref=start();message('Treatment change requested',ref)
    task(k,'SP09_Assess',{'urgentChange':False,'formallyAuthorised':True,'financialImpact':True})
    work(k,'hpas-sp09-validate-formal',{'formallyAuthorised':True})
    task(k,'SP09_FinanceReview',{'financialReviewApproved':True,'refundRequired':False})
    wait_for(lambda:active(k,'SP09_ApplyChange'),'approved change')
    assert done(k,'SP09_FinanceReview')
def letter_revision():
    k,ref=start();message('Clinic letter required',ref)
    task(k,'SP13_Draft',{'clinicLetterDraft':'TEST','letterRecipients':'TEST'})
    task(k,'SP13_Approve',{'letterApproved':False})
    wait_for(lambda:active(k,'SP13_Draft'),'return to author');assert not active(k,'SP13_Distribute')
    task(k,'SP13_Draft',{'clinicLetterDraft':'TEST revised','letterRecipients':'TEST'})
    task(k,'SP13_Approve',{'letterApproved':True})
    task(k,'SP13_AdminProcess',{'adminCheck':'ok','followUpInstructed':False})
    wait_for(lambda:active(k,'SP13_Distribute'),'approved distribution')
def transfer():
    k,ref=start();message('Refund case opened',ref)
    task(k,'SP11_Determine',{'refundDue':True,'refundAuthorised':True,'refundScope':'transfer','refundAmount':10,'financeApprover':'TEST','transferDestination':'TEST-ACCOUNT'})
    wait_for(lambda:active(k,'SP11_Transfer'),'transfer route');assert not active(k,'SP11_SendRefund')
def unauthorised_refund():
    k,ref=start();message('Refund case opened',ref)
    task(k,'SP11_Determine',{'refundDue':True,'refundAuthorised':False,'refundScope':'full','refundAmount':10,'financeApprover':'TEST'})
    wait_for(lambda:active(k,'SP11_RecordNone'),'no unauthorised refund');assert not active(k,'SP11_SendRefund')
def refund_failure():
    k,ref=start();message('Refund case opened',ref)
    task(k,'SP11_Determine',{'refundDue':True,'refundAuthorised':True,'refundScope':'partial','refundAmount':10,'financeApprover':'TEST'})
    rid='TEST-REFUND-'+k
    work(k,'hpas-sp11-send-refund',{'refundRequestId':rid})
    wait_for(lambda:active(k,'SP11_Catch_Result'),'refund wait')
    message('Refund result',rid,{'refundStatus':'failed'})
    wait_for(lambda:active(k,'SP11_Determine'),'failed refund reviewed');assert not done(k,'SP11_Record')
def restore():
    k,ref=start();message('Access or interruption event',ref,{'eventKind':'interruption'})
    reference='TEST-INC-'+k
    task(k,'SP16_RecordDowntime',{'incidentReference':reference,'offlineActions':'TEST'})
    wait_for(lambda:active(k,'SP16_WaitRestored'),'restoration wait')
    message('Service restored',reference+'-WRONG');time.sleep(.5)
    assert active(k,'SP16_WaitRestored') and not active(k,'SP16_Reconcile')
    message('Service restored',reference);wait_for(lambda:active(k,'SP16_Reconcile'),'matching restoration')
def denied_access():
    k,ref=start();message('Access or interruption event',ref,{'eventKind':'access'})
    task(k,'SP16_VerifyAuth',{'accessAuthorised':False,'staffIdentity':'TEST','requestedAccess':'TEST'})
    wait_for(lambda:active(k,'SP16_Audit'),'denied access audit');assert not done(k,'SP16_ApplyAccess')
def financial_enquiry():
    k,ref=start();message('Patient enquiry received',ref)
    task(k,'SP12_Record',{'enquiryClass':'financial','infoAuthorised':False})
    wait_for(lambda:active(k,'SP12_ReferFinance'),'finance referral')
def parallel_slots():
    k,ref=start();message('Appointment change requested',ref);message('Follow-up instruction authorised',ref)
    task(k,'SP03B_Verify',{'identityVerified':True,'requestedReviewBy':'2099-01-01','changeKind':'reschedule'})
    task(k,'SP14_Instruction',{'requestedReviewBy':'2099-01-01','followUpClinician':'TEST'})
    work(k,'hpas-sp03b-search-slot',{'schedulingRequestId':'TEST-CHANGE-'+k})
    work(k,'hpas-sp14-search-slot',{'schedulingRequestId':'TEST-FOLLOW-'+k})
    wait_for(lambda:active(k,'SP03B_Catch_Result') and active(k,'SP14_Catch_Result'),'parallel waits')
    message('SP14 scheduling result','TEST-FOLLOW-'+k,{'inRequestedPeriod':True,'appointmentDate':'2099-01-01'})
    wait_for(lambda:active(k,'SP14_Book'),'follow-up only');assert active(k,'SP03B_Catch_Result')
    message('SP03B scheduling result','TEST-CHANGE-'+k,{'slotAcceptable':True,'appointmentDate':'2099-01-01'})
    wait_for(lambda:active(k,'SP03B_ValidateSlot'),'change reply')
def followup_retry():
    k,ref=start();message('Follow-up instruction authorised',ref)
    task(k,'SP14_Instruction',{'requestedReviewBy':'2099-01-01','followUpClinician':'TEST'})
    work(k,'hpas-sp14-search-slot',{'schedulingRequestId':'TEST-RETRY-'+k})
    wait_for(lambda:active(k,'SP14_Catch_Result'),'follow-up wait')
    message('SP14 scheduling result','TEST-RETRY-'+k,{'inRequestedPeriod':False})
    task(k,'SP14_Highlight',{'followUpResolution':'retry','followUpExceptionAuthorised':False})
    wait_for(lambda:active(k,'SP14_RequestSlot'),'retry search')

if __name__=='__main__':
    source=ROOT/'bpmn/hospital-patient-pathway.bpmn'
    (ROOT/'evidence/subprocess-repair-20261020/runtime-input.bpmn.keep').write_bytes(source.read_bytes())
    xml=source.read_bytes().replace(b'Process_HPAS',b'Process_HPAS_SubRepair_Test')
    xml=xml.replace(b'type="hpas-',('type="TEST-SUB-'+RUN+':hpas-').encode())
    files=[('resources',('subprocess-test.bpmn',xml,'application/xml'))]
    files.extend(('resources',(p.name,p.read_bytes(),'application/json')) for p in sorted((ROOT/'bpmn').glob('*.form')))
    deployment=requests.post(BASE+'/deployments',files=files,timeout=30)
    if not deployment.ok:raise RuntimeError(deployment.text)
    result=deployment.json()
    DEFINITION=next(x['processDefinition']['processDefinitionKey'] for x in result['deployments'] if x.get('processDefinition') and x['processDefinition']['processDefinitionId']=='Process_HPAS_SubRepair_Test')
    report={'run':RUN,'baseUrl':BASE,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'testDefinitionKey':DEFINITION,'mode':'real engine, API synthetic tasks, controlled jobs; no browser/real upstream claim','cases':RESULTS}
    try:
        for name,fn in [('finance-refusal',finance_refusal),('finance-approval-before-apply',finance_approval),('letter-revision-and-approval',letter_revision),('transfer-not-refund',transfer),('unauthorised-refund',unauthorised_refund),('failed-refund-reviewed',refund_failure),('restoration-correlation',restore),('access-denied',denied_access),('financial-enquiry',financial_enquiry),('parallel-scheduling-isolation',parallel_slots),('followup-retry',followup_retry)]:case(name,fn)
        for key in CREATED:assert not api('/process-instances/'+key,method='GET').get('hasIncident'),key
    finally:
        report['testInstances']=CREATED
        for key in CREATED:
            try:api('/process-instances/'+key+'/cancellation',{})
            except Exception as exc:report.setdefault('cleanupErrors',[]).append(str(exc))
        report['passed']=len(RESULTS)==11
        (ROOT/'evidence/subprocess-repair-20261020/runtime-results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')

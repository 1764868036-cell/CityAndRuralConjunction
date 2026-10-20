"""Post-deployment smoke tests with real 8080 workers and synthetic data only."""
from pathlib import Path
import requests,json,time,uuid,hashlib
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'evidence/subprocess-layout-deploy-20261020'
BASE='http://127.0.0.1:8080/v2';RUN=uuid.uuid4().hex[:8];CREATED=[];RESULTS=[]
deployment=json.loads((OUT/'deployment.json').read_text())
DEFINITION=next(x['processDefinition']['processDefinitionKey'] for x in deployment['deployments'] if x.get('processDefinition'))
def api(path,data=None,method='POST'):
 r=requests.request(method,BASE+path,json=data,timeout=20);r.raise_for_status();return r.json() if r.content else {}
def search(type,key):return api('/'+type+'/search',{'filter':{'processInstanceKey':key},'page':{'limit':100}})['items']
def wait(fn,label):
 end=time.monotonic()+35
 while time.monotonic()<end:
  v=fn()
  if v:return v
  time.sleep(.3)
 raise AssertionError('Timed out: '+label)
def state(key,id,state):return any(e['elementId']==id and e['state']==state for e in search('element-instances',key))
def msg(name,key,data=None):return api('/messages/publication',{'name':'HPAS: '+name,'correlationKey':key,'messageId':str(uuid.uuid4()),'timeToLive':60000,'variables':data or {}})
def start():
 ref='TEST-8080-DEPLOY-'+RUN+'-'+str(len(CREATED));pi=api('/process-instances',{'processDefinitionKey':DEFINITION,'variables':{'referralId':ref,'patientId':'TEST-SYNTHETIC','referralReceived':False}})['processInstanceKey'];CREATED.append(pi)
 wait(lambda:state(pi,'Top_Catch_Referral','ACTIVE'),'root ready');return pi,ref
def complete(pi,id,values):
 t=wait(lambda:next((x for x in search('user-tasks',pi) if x['elementId']==id and x['state']=='CREATED'),None),id)
 api('/user-tasks/'+t['userTaskKey']+'/completion',{'variables':values})
def record(case,pi,ids):
 for id in ids:wait(lambda:state(pi,id,'COMPLETED'),id)
 assert not api('/process-instances/'+pi,method='GET').get('hasIncident')
 RESULTS.append({'case':case,'instanceKey':pi,'passed':True,'completedElements':ids});print('PASS',case,pi,flush=True)
try:
 k,ref=start();msg('Appointment change requested',ref)
 complete(k,'SP03B_Verify',{'identityVerified':True,'requestedReviewBy':'2099-01-01','changeKind':'reschedule','SP03B_Verify_actor':'TEST'})
 record('real-slot-reply-date-worker-and-reschedule',k,['SP03B_ValidateSlot','SP03B_Rebook','SP03B_End'])
 k,ref=start();msg('Refund case opened',ref)
 complete(k,'SP11_Determine',{'refundDue':True,'refundAuthorised':True,'refundScope':'transfer','refundAmount':10,'financeApprover':'TEST','transferDestination':'TEST-DESTINATION','refundReason':'TEST synthetic adjustment','SP11_Determine_actor':'TEST'})
 record('real-transfer-instruction-worker',k,['SP11_Transfer','SP11_End']);assert not state(k,'SP11_SendRefund','COMPLETED')
 k,ref=start();msg('Treatment change requested',ref)
 complete(k,'SP09_Assess',{'urgentChange':False,'formallyAuthorised':True,'financialImpact':True,'authorisingClinician':'TEST','changeReason':'TEST','SP09_Assess_actor':'TEST'})
 complete(k,'SP09_FinanceReview',{'financialReviewApproved':False,'refundRequired':False,'financialChangeNotes':'TEST refused','SP09_FinanceReview_actor':'TEST'})
 record('real-finance-refusal-before-apply',k,['SP09_ValidateFormal','SP09_End_Rejected']);assert not state(k,'SP09_ApplyChange','COMPLETED')
 k,ref=start();msg('Access or interruption event',ref,{'eventKind':'interruption'})
 incident='TEST-RESTORE-'+RUN;complete(k,'SP16_RecordDowntime',{'incidentReference':incident,'offlineActions':'TEST synthetic offline actions','SP16_RecordDowntime_actor':'TEST'})
 wait(lambda:state(k,'SP16_WaitRestored','ACTIVE'),'restoration wait');msg('Service restored',incident+'-WRONG');time.sleep(.7)
 assert state(k,'SP16_WaitRestored','ACTIVE');msg('Service restored',incident)
 record('real-restoration-correlation-and-reconcile',k,['SP16_WaitRestored','SP16_Reconcile','SP16_End'])
finally:
 cleanup=[]
 for k in CREATED:
  try:api('/process-instances/'+k+'/cancellation',{});cleanup.append(k)
  except Exception as exc:cleanup.append({'key':k,'error':str(exc)})
 result={'definitionKey':DEFINITION,'runId':RUN,'sourceSha256':hashlib.sha256((ROOT/'bpmn/hospital-patient-pathway.bpmn').read_bytes()).hexdigest(),'mode':'real 8080 engine and Java workers; synthetic API form submissions','cases':RESULTS,'cleanup':cleanup,'passed':len(RESULTS)==4}
 (OUT/'smoke-results.json').write_text(json.dumps(result,indent=2)+'\n')

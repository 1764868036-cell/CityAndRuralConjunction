"""Current-source regression checks; deliberately independent of the frozen acceptance snapshot."""
import unittest
from pathlib import Path
from lxml import etree as E

ROOT = Path(__file__).resolve().parents[1]
B = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
Z = '{http://camunda.org/schema/zeebe/1.0}'

class SubprocessRepairs(unittest.TestCase):
    def setUp(self):
        self.r = E.parse(str(ROOT/'bpmn/hospital-patient-pathway.bpmn')).getroot()
    def node(self, id):
        return self.r.xpath('//*[@id=$id]', id=id)[0]
    def path(self, scope, start, end, without=()):
        flows=self.node(scope).findall(B+'sequenceFlow');todo=[start];seen=set(without)
        while todo:
            n=todo.pop()
            if n in seen: continue
            if n==end:return True
            seen.add(n);todo.extend(f.get('targetRef') for f in flows if f.get('sourceRef')==n)
        return False
    def test_finance_precedes_change_and_can_refuse(self):
        self.assertTrue(self.path('SP09','SP09_FinanceReview','SP09_ApplyChange'))
        self.assertFalse(self.path('SP09','SP09_ApplyChange','SP09_FinanceReview'))
        self.assertTrue(self.path('SP09','SP09_FinanceReview','SP09_End_Rejected'))
    def test_change_inputs_collected_before_gate(self):
        self.assertEqual(self.node('Flow_137').get('targetRef'),'SP09_Assess')
    def test_refund_and_transfer_are_separate(self):
        self.node('SP11_Transfer')
        conditions=' '.join(x.text or '' for x in self.node('SP11').iter(B+'conditionExpression'))
        self.assertIn('refundAuthorised',conditions)
        self.assertIn('refundScope',conditions)
        self.node('SP11_GW_Result')
    def test_letter_rejection_returns_to_draft(self):
        self.assertTrue(self.path('SP13_PrepareScope','SP13_Approve','SP13_Draft'))
        self.assertFalse(self.path('SP13_PrepareScope','SP13_Approve','SP13_PS_End',{'SP13_GW_Approved'}))
    def test_restoration_is_correlated_and_awaited(self):
        self.assertFalse(self.path('SP16','SP16_RecordDowntime','SP16_Reconcile',{'SP16_WaitRestored'}))
        msg=self.node('SP16_WaitRestored').find(B+'messageEventDefinition').get('messageRef')
        self.assertEqual(self.node(msg).find(B+'extensionElements/'+Z+'subscription').get('correlationKey'),'= incidentReference')
    def test_scheduling_replies_are_isolated(self):
        refs=[self.node(x).find(B+'messageEventDefinition').get('messageRef') for x in ['SP03_Catch_Result','SP03B_Catch_Result','SP14_Catch_Result'] if self.r.xpath('//*[@id=$id]',id=x)]
        self.assertEqual(len(refs),len(set(refs)))
        for id in ['SP03B_Catch_Result','SP14_Catch_Result']:
            msg=self.node(id).find(B+'messageEventDefinition').get('messageRef')
            self.assertEqual(self.node(msg).find(B+'extensionElements/'+Z+'subscription').get('correlationKey'),'= schedulingRequestId')
    def test_financial_enquiry_has_preceding_authorisation_field(self):
        import json
        form=json.loads((ROOT/'bpmn/hpas_sp12_record.form').read_text(encoding='utf-8'))
        self.assertIn('infoAuthorised',{x.get('key') for x in form['components']})
    def test_followup_conflict_can_retry(self):
        self.assertTrue(self.path('SP14','SP14_Highlight','SP14_RequestSlot'))
    def test_change_identity_is_explicit(self):
        self.node('SP03B_Verify')
        self.assertFalse(self.path('SP03B','SP03B_Start','SP03B_RequestSlot',{'SP03B_Verify'}))

if __name__=='__main__': unittest.main()

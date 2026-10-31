"""Offline failure-injection tests; no JDBC, HTTP, Redis or Docker operations."""
import json,pathlib,queue,tempfile,unittest
from unittest.mock import Mock,patch
import controlled_dispatch_compare as subject

class ControlTests(unittest.TestCase):
 def channel(self,reply):
  db=subject.Database.__new__(subject.Database);db.lock=__import__('threading').Lock();db.events=[]
  db.proc=Mock();db.replies=Mock();db.close=Mock()
  if isinstance(reply,Exception):db.replies.get.side_effect=reply
  else:db.replies.get.return_value=reply
  return db
 def test_phase_timeout_closes_channel_and_is_explicit(self):
  db=self.channel(queue.Empty())
  with self.assertRaisesRegex(TimeoutError,'initialize-schema.*35s'):db.call('initialize-schema')
  db.close.assert_called_once();db.replies.get.assert_called_once_with(timeout=35)
  self.assertEqual(db.events[0]['phase'],'initialize-schema')
 def test_stale_response_rejected(self):
  db=self.channel(dict(id='old',ok=True))
  with self.assertRaisesRegex(RuntimeError,'ID mismatch'):db.call('query')
 def test_matching_response_and_phase_timing(self):
  db=self.channel(dict(id='fixed',ok=True,operation_ms=12))
  with patch.object(subject.uuid,'uuid4',return_value=Mock(hex='fixed')):db.call('initialize-data')
  self.assertEqual(db.events[0]['operation_ms'],12)
 def test_partial_create_timeout_uses_new_cleanup_channel(self):
  first=Mock();first.events=[];first.proc.poll.return_value=1
  first.call.side_effect=TimeoutError('injected partial create timeout')
  cleanup=Mock();cleanup.events=[]
  with tempfile.TemporaryDirectory() as folder:
   with patch.object(subject,'Database',side_effect=[first,cleanup]),patch.object(subject,'code_identity',return_value={}),patch.object(subject,'memory_gate',return_value=dict(ok=True)),patch.object(subject.socket,'socket'):
    with self.assertRaisesRegex(TimeoutError,'partial create'):subject.run_one('A',0,pathlib.Path(folder),{},dict(CREDIT_RISK_MYSQL_ROOT_PASSWORD='fake'),preflight=True)
   out=pathlib.Path(folder)/'preflight-A';journal=json.loads((out/'ownership.json').read_text());result=json.loads((out/'cleanup.json').read_text())
   self.assertEqual(journal['phase'],'before-create');self.assertTrue(result['owned_schema_removed']);cleanup.call.assert_called_once_with('cleanup');cleanup.close.assert_called_once()
 def test_cleanup_failure_remains_visible(self):
  db=Mock();db.events=[];db.proc.poll.return_value=None;db.call.side_effect=[TimeoutError('partial'),RuntimeError('owner mismatch')]
  with tempfile.TemporaryDirectory() as folder:
   with patch.object(subject,'Database',return_value=db),patch.object(subject,'code_identity',return_value={}),patch.object(subject,'memory_gate',return_value=dict(ok=True)),patch.object(subject.socket,'socket'):
    with self.assertRaisesRegex(RuntimeError,'owner mismatch'):subject.run_one('A',0,pathlib.Path(folder),{},dict(CREDIT_RISK_MYSQL_ROOT_PASSWORD='fake'),preflight=True)
   result=json.loads((pathlib.Path(folder)/'preflight-A/cleanup.json').read_text());self.assertIn('owner mismatch',result['cleanup_error']);db.close.assert_called_once()

if __name__=='__main__':unittest.main()

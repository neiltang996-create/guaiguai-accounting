import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tarfile
import tempfile
import threading
import unittest
import urllib.request
import urllib.error

spec = importlib.util.spec_from_file_location('server', Path(__file__).with_name('familyledger_sync_server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)

class SyncServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        server.CONFIG.update(data_dir=self.temp.name+'/data', token='test-only-token', verbose=False)
        Path(server.CONFIG['data_dir']).mkdir()
        self.http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:%d' % self.http.server_port
    def tearDown(self):
        self.http.shutdown(); self.http.server_close(); self.thread.join(); self.temp.cleanup()
    def request(self, path, body=None, token='test-only-token'):
        request = urllib.request.Request(self.base+path, data=body,
            headers={'X-Sync-Token': token, 'X-Family-Id': 'TEST_FAMILY'})
        try:
            with urllib.request.urlopen(request, timeout=3) as r: return r.status, r.read()
        except urllib.error.HTTPError as error: return error.code, error.read()
    def op(self, dev, seq, cents):
        return {'opId': f'{dev}:{seq}', 'deviceId': dev, 'seq': seq, 'entityTable': 'txn',
                'entityId': f't-{dev}-{seq}', 'opType': 'UPSERT',
                'payloadJson': json.dumps({'id': f't-{dev}-{seq}', 'amount': cents}), 'hlc': seq, 'createdAt': seq}
    def post(self, *ops): return self.request('/ops', b'\n'.join(json.dumps(x).encode() for x in ops))
    def test_unconfigured_token_fails_closed(self):
        server.CONFIG['token'] = ''
        self.assertEqual(503, self.request('/health')[0])
    def test_retries_are_idempotent_and_conflicts_rejected(self):
        op = self.op('dev-a', 1, 3000)
        self.assertEqual(200, self.post(op)[0]); self.assertEqual(200, self.post(op,op)[0])
        self.assertEqual(1, json.loads(self.request('/ops/dev-a/count')[1])['count'])
        self.assertEqual(409, self.post(self.op('dev-a',1,5000))[0])
        self.assertEqual(1, json.loads(self.request('/ops/dev-a/count')[1])['count'])
    def test_two_devices_concurrent_and_replay_balance(self):
        ops=[self.op('dev-tang',1,3000),self.op('dev-liu',1,5000)]
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            responses=list(pool.map(lambda op:self.post(op)[0],ops*10))
        self.assertEqual({200},set(responses))
        records=[]
        for dev in ['dev-tang','dev-liu']:
            records.extend(json.loads(line) for line in self.request('/ops/'+dev)[1].splitlines())
        self.assertEqual(2,len(records))
        self.assertEqual(8000,sum(json.loads(x['payloadJson'])['amount'] for x in records))
    def test_bad_batch_is_not_partially_applied(self):
        good=json.dumps(self.op('dev-a',1,3000)).encode()
        self.assertEqual(400,self.request('/ops',good+b'\nnot json')[0])
        self.assertEqual(0,json.loads(self.request('/ops/dev-a/count')[1])['count'])
    def test_token_required(self):
        self.assertEqual(401,self.request('/health',token='wrong')[0])
        self.assertEqual(200,self.request('/health')[0])
    def test_backup_restore_and_retention(self):
        self.post(self.op('dev-a',1,3000))
        folder=self.temp.name+'/backups'
        archives=[server.backup_snapshot(folder,2) for _ in range(3)]
        self.assertEqual(2,len(list(Path(folder).glob('*.tar.gz'))))
        latest=Path(archives[-1])
        self.assertEqual(hashlib.sha256(latest.read_bytes()).hexdigest(),Path(str(latest)+'.sha256').read_text().split()[0])
        with tarfile.open(latest) as archive:
            restored=archive.extractfile('data/TEST_FAMILY/dev-a.jsonl').read()
        self.assertEqual(self.request('/ops/dev-a')[1],restored)
    def test_receipt_attachment_auth_hash_isolation_and_idempotency(self):
        image = b'\xff\xd8test receipt bytes\xff\xd9'
        digest = hashlib.sha256(image).hexdigest()
        path = '/attachments/' + digest
        self.assertEqual(401, self.request(path, image, token='wrong')[0])
        self.assertEqual(404, self.request(path)[0])
        self.assertEqual(400, self.request('/attachments/' + 'a'*64, image)[0])
        self.assertEqual(400, self.request('/attachments/../outside', image)[0])
        self.assertEqual(200, self.request(path, image)[0])
        self.assertEqual(200, self.request(path, image)[0])
        self.assertEqual(image, self.request(path)[1])
        self.assertFalse(Path(self.temp.name + '/data/OTHER/receipts/' + digest + '.jpg').exists())
        self.assertEqual([], list(Path(self.temp.name + '/data/TEST_FAMILY/receipts').glob('*.tmp')))

    def test_two_initial_family_claims_are_serialized(self):
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results=list(pool.map(lambda name:self.request('/family',json.dumps({'familyId':name}).encode())[0],['FAMILY_A','FAMILY_B']))
        self.assertEqual([200,409],sorted(results))

if __name__=='__main__': unittest.main(verbosity=2)

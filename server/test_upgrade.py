import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import upgrade_on_nas as upgrade


class UpgradeTest(unittest.TestCase):
    def simulate(self, fail=None, wrong_mount=False):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            (base / 'data').mkdir()
            (base / 'data' / 'ledger.jsonl').write_text('{"amount":3000}\n')
            calls = []
            state = {'renamed': False, 'replacement': False}
            old = {'State': {'Running': True}, 'Mounts': [{'Destination': '/data', 'Source': str(base / ('wrong' if wrong_mount else 'data'))}],
                   'Config': {'Env': ['FAMILYLEDGER_TOKEN=private-test-token']},
                   'HostConfig': {'PortBindings': {'47822/tcp': [{'HostPort': '47822', 'HostIp': '127.0.0.1'}]}}}
            def docker(*args, capture=False):
                calls.append(args)
                if args[0] == 'inspect':
                    if state['replacement']:
                        return json.dumps([{'Config': {'Labels': {'familyledger.upgrade': 'STAMP'}}}])
                    if state['renamed']:
                        raise subprocess.CalledProcessError(1, 'docker inspect')
                    return json.dumps([old])
                if args[0] == 'build' and fail == 'build':
                    raise RuntimeError('image build failed')
                if args[0] == 'rename':
                    state['renamed'] = args[1] == upgrade.NAME
                if args[0] == 'compose' and 'up' in args:
                    state['replacement'] = True
                    if fail == 'compose':
                        raise RuntimeError('partial compose failure')
                if args[0] == 'rm':
                    state['replacement'] = False
                return ''
            def health(*args, **kwargs):
                if fail == 'health':
                    raise OSError('unhealthy')
                return io.BytesIO(b'{"app":"familyledger"}')
            with patch.object(upgrade, 'BASE', base), patch.object(upgrade, 'run', docker), patch.object(upgrade.os, 'geteuid', return_value=0), patch.object(upgrade.os, 'chown'), patch.object(upgrade.time, 'strftime', return_value='STAMP'), patch.object(upgrade.time, 'sleep'), patch.object(upgrade.urllib.request, 'urlopen', health), contextlib.redirect_stdout(io.StringIO()) as out:
                if fail or wrong_mount:
                    with self.assertRaises((RuntimeError, SystemExit)):
                        upgrade.main()
                else:
                    upgrade.main()
                    self.assertTrue((base / 'upgrade-STAMP' / 'SUCCESS').exists())
                    config = json.loads((base / 'upgrade-STAMP' / 'compose.json').read_text())
                    service = config['services'][upgrade.NAME]
                    self.assertEqual('private-test-token', service['environment']['FAMILYLEDGER_TOKEN'])
                    self.assertEqual('127.0.0.1', service['ports'][0]['host_ip'])
                self.assertNotIn('private-test-token', out.getvalue())
                self.assertEqual('{"amount":3000}\n', (base / 'data' / 'ledger.jsonl').read_text())
            return calls
    def test_success_preserves_token_binding_and_data(self):
        self.simulate()
    def test_build_failure_never_stops_service(self):
        calls = self.simulate('build')
        self.assertFalse(any(c[0] == 'stop' for c in calls))
    def test_health_failure_restores_old_container(self):
        calls = self.simulate('health')
        self.assertIn(('start', upgrade.NAME), calls)
        self.assertIn(('rename', upgrade.NAME + '-before-STAMP', upgrade.NAME), calls)
    def test_partial_compose_failure_restores_old_container(self):
        calls = self.simulate('compose')
        self.assertIn(('rm', '-f', upgrade.NAME), calls)
        self.assertIn(('start', upgrade.NAME), calls)
    def test_wrong_mount_refuses_before_build(self):
        calls = self.simulate(wrong_mount=True)
        self.assertFalse(any(c[0] == 'build' for c in calls))

if __name__ == '__main__':
    unittest.main()

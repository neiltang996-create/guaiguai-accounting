"""Unprivileged image startup, HTTP, and backup check using temporary data only."""
import json
from pathlib import Path
import tempfile
import threading
import urllib.request
from http.server import ThreadingHTTPServer
import familyledger_sync_server as service

with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    data = root / 'data'
    data.mkdir()
    service.CONFIG.update(data_dir=str(data), token='isolated-image-test', verbose=False)
    httpd = ThreadingHTTPServer(('127.0.0.1', 0), service.Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        req = urllib.request.Request('http://127.0.0.1:%d/health' % httpd.server_port,
                                     headers={'X-Sync-Token': 'isolated-image-test'})
        with urllib.request.urlopen(req, timeout=3) as response:
            assert json.load(response)['app'] == 'familyledger'
        assert Path(service.backup_snapshot(str(root / 'backups'))).exists()
        print('Isolated image startup, HTTP and backup checks passed.')
    finally:
        httpd.shutdown()
        httpd.server_close()

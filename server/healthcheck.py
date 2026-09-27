import os
import urllib.request
request = urllib.request.Request('http://127.0.0.1:47822/health', headers={'X-Sync-Token': os.environ['FAMILYLEDGER_TOKEN']})
with urllib.request.urlopen(request, timeout=4) as response:
    assert response.status == 200

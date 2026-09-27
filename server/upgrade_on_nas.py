#!/usr/bin/env python3
"""Run with sudo on the NAS. Preserve the existing token, data and old container.
Only manages the exact familyledger-sync container and its verified data mount.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import time
import urllib.request

DOCKER = '/usr/local/bin/docker'
BASE = Path('/volume1/docker/familyledger')
NAME = 'familyledger-sync'
HERE = Path(__file__).resolve().parent


def run(*args, capture=False):
    return subprocess.check_output([DOCKER, *args], text=True).strip() if capture else subprocess.check_call([DOCKER, *args])


def private_json(path, obj):
    with path.open('x') as f:
        os.chmod(path, 0o600)
        json.dump(obj, f, ensure_ascii=False, indent=2)


def main():
    if os.geteuid() != 0:
        raise SystemExit('请在 NAS 上用 sudo python3 upgrade_on_nas.py 执行。')
    os.umask(0o077)
    old = json.loads(run('inspect', NAME, capture=True))[0]
    if not old['State']['Running']:
        raise SystemExit('原容器未运行，停止自动升级，请先检查。')
    mount = next((m for m in old['Mounts'] if m['Destination'] == '/data'), None)
    if not mount or Path(mount['Source']).resolve() != (BASE / 'data').resolve():
        raise SystemExit('数据挂载与预期不符，未做更改。')
    env = dict(e.split('=', 1) for e in old['Config']['Env'] if '=' in e)
    token = env.get('FAMILYLEDGER_TOKEN')
    if not token:
        raise SystemExit('无法从旧容器环境读取现有令牌，未做更改。')
    bindings = old['HostConfig']['PortBindings'].get('47822/tcp', [])
    if not bindings or any(b['HostPort'] != '47822' for b in bindings):
        raise SystemExit('原端口配置与预期不符，未做更改。')
    run('compose', 'version')
    stamp = time.strftime('%Y%m%d-%H%M%S')
    image = 'familyledger-sync:1.2-' + stamp
    # Image build failure leaves the live container untouched.
    run('build', '-t', image, str(HERE))
    run('run', '--rm', '--network', 'none', '--entrypoint', 'python3', image, '/app/smokecheck.py')
    state = BASE / ('upgrade-' + stamp)
    state.mkdir(mode=0o700)
    private_json(state / 'previous-container.json', old)
    backup_dir = BASE / 'backups'
    backup_dir.mkdir(exist_ok=True)
    data_stat = (BASE / 'data').stat()
    os.chown(backup_dir, data_stat.st_uid, data_stat.st_gid)
    config = {'services': {NAME: {
        'image': image, 'container_name': NAME, 'restart': 'unless-stopped',
        'user': f'{data_stat.st_uid}:{data_stat.st_gid}',
        'labels': {'familyledger.upgrade': stamp},
        'ports': [{'target': 47822, 'published': int(b['HostPort']),
                   'host_ip': b.get('HostIp') or '0.0.0.0', 'protocol': 'tcp'} for b in bindings],
        'environment': {'FAMILYLEDGER_DATA': '/data', 'FAMILYLEDGER_PORT': '47822',
                        'FAMILYLEDGER_TOKEN': token, 'FAMILYLEDGER_BACKUP_DIR': '/backup',
                        'FAMILYLEDGER_BACKUP_KEEP': '30'},
        'volumes': [str(BASE / 'data') + ':/data', str(backup_dir) + ':/backup']}}}
    compose = state / 'compose.json'
    private_json(compose, config)
    old_name = NAME + '-before-' + stamp
    stopped = renamed = new_started = False
    try:
        run('stop', '--time', '30', NAME)
        stopped = True
        # Capture a consistent pre-upgrade backup while the old service is stopped.
        snapshot = state / 'data-before-upgrade.tar.gz'
        with tarfile.open(snapshot, 'w:gz') as archive:
            archive.add(BASE / 'data', arcname='data')
        with tarfile.open(snapshot, 'r:gz') as archive:
            if not archive.getmembers():
                raise RuntimeError('备份为空')
            for member in archive:
                if member.isfile():
                    f = archive.extractfile(member)
                    while f.read(1024 * 1024):
                        pass
        checksum = hashlib.sha256(snapshot.read_bytes()).hexdigest()
        (state / 'data-before-upgrade.tar.gz.sha256').write_text(checksum + '\n')
        run('rename', NAME, old_name)
        renamed = True
        run('compose', '-p', 'familyledger-upgrade-' + stamp, '-f', str(compose), 'up', '-d', '--no-build')
        new_started = True
        request = urllib.request.Request('http://127.0.0.1:47822/health', headers={'Authorization': 'Bearer ' + token})
        for attempt in range(20):
            try:
                with urllib.request.urlopen(request, timeout=3) as response:
                    health = json.load(response)
                if health.get('app') == 'familyledger':
                    break
            except Exception:
                pass
            time.sleep(1)
        else:
            raise RuntimeError('新服务健康检查失败')
        (state / 'SUCCESS').write_text('Previous container: ' + old_name + '\n')
        print('升级成功；已保留旧容器、升级前备份和原数据。记录：' + str(state))
        print('每日备份目录：' + str(backup_dir) + '，保留最近 30 份。请从手机再次验证外网同步。')
    except BaseException:
        # Remove only a replacement created by this exact upgrade, never another service.
        try:
            current = json.loads(run('inspect', NAME, capture=True))[0]
            if current['Config'].get('Labels', {}).get('familyledger.upgrade') == stamp:
                run('rm', '-f', NAME)
        except subprocess.CalledProcessError:
            pass
        if renamed:
            run('rename', old_name, NAME)
        if stopped:
            run('start', NAME)
        print('升级失败，已恢复原容器。原数据目录未被替换。')
        raise


if __name__ == '__main__':
    main()

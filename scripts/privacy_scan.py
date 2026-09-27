#!/usr/bin/env python3
"""Conservative local privacy gate. Never prints matched secret values.
Gitleaks and manual image review are still required; this is not an exhaustive security audit.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = {
    'private-key': re.compile(rb'-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----'),
    'provider-token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,}|sk-[A-Za-z0-9_-]{25,}|AKIA[0-9A-Z]{16})\b'),
    'personal-home-path': re.compile(rb'/(?:Users|home)/[A-Za-z0-9_.-]+/'),
    'personal-relay-host': re.compile(rb'https?://[A-Za-z0-9.-]+\.quickconnect\.[A-Za-z.]+'),
    'mobile-number-candidate': re.compile(rb'(?<!\d)1[3-9]\d{9}(?!\d)'),
}
FORBIDDEN_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.db', '.sqlite', '.sqlite3', '.xlsx', '.xls', '.csv', '.jsonl', '.log', '.zip', '.tar', '.tgz', '.bak'}
EXCLUDED_DIRS = {'.git', '.gradle', '.kotlin', 'build', '__pycache__', '.idea'}

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], stderr=subprocess.DEVNULL)

def path_problem(name):
    p = Path(name)
    if p.name == 'local.properties' or (p.name.startswith('.env') and p.name != '.env.example'):
        return 'private-config'
    if p.name.startswith('config.local.') and not p.name.endswith('.example'):
        return 'private-config'
    if p.suffix.lower() in FORBIDDEN_SUFFIXES or any(x in p.parts for x in ('keystore', 'private', 'receipts')):
        return 'private-artifact'
    if p.suffix.lower() in ('.png', '.jpg', '.jpeg', '.webp') and not name.startswith('docs/images/'):
        return 'unreviewed-image-location'
    return None

def scan(name, data):
    found=[]
    reason=path_problem(name)
    if reason: found.append((name, 0, reason))
    if Path(name).suffix.lower() in ('.png','.jpg','.jpeg','.webp','.jar'):
        return found # Reviewed separately by image manifest / Gradle wrapper checksum.
    for rule, rx in RULES.items():
        for hit in list(rx.finditer(data))[:5]:
            found.append((name, data.count(b'\n',0,hit.start())+1, rule))
    return found

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--history',action='store_true')
    args=parser.parse_args()
    try:
        names=git('ls-files','--cached','--others','--exclude-standard','-z').decode().split('\0')
        names=sorted(set(n for n in names if n and (ROOT/n).is_file()))
    except subprocess.CalledProcessError:
        names=[str(p.relative_to(ROOT)) for p in ROOT.rglob('*') if p.is_file() and not EXCLUDED_DIRS.intersection(p.relative_to(ROOT).parts)]
    findings=[]
    for name in names:
        findings.extend(scan(name,(ROOT/name).read_bytes()))
    objects=0; commits=0
    if args.history:
        try:
            revisions=git('rev-list','--all').decode().splitlines()
            commits=len(revisions)
            blobs={}
            for revision in revisions:
                findings.extend(scan('commit:'+revision,git('cat-file','commit',revision)))
                for record in git('ls-tree','-r','-z',revision).split(b'\0'):
                    if not record:continue
                    meta,name=record.split(b'\t',1)
                    mode,kind,oid=meta.split()
                    if kind==b'blob':blobs.setdefault((oid.decode(),name.decode()),None)
            for oid,name in blobs:
                findings.extend(scan(name,git('cat-file','blob',oid)));objects+=1
        except subprocess.CalledProcessError:
            print('History scan requires a Git repository.',file=sys.stderr);return 2
    for name,line,rule in findings:
        print(f'{name}:{line}: {rule}')
    print(f'privacy scan: {len(names)} working files; {commits} commits; {objects} historical file versions; {len(findings)} findings')
    return 1 if findings else 0

if __name__=='__main__':
    sys.exit(main())

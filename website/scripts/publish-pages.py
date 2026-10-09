"""Publish only the reviewed website and its Pages workflow; preserve app state."""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

PROJECT = Path(__file__).resolve().parents[1]
WORKSPACE = PROJECT.parent
REPO = 'syczk301/pickup-assistant'
BASE = 'https://api.github.com/repos/' + REPO
AUDIT = WORKSPACE / 'deliverables/website-publication.json'


def credentials():
    result = subprocess.run(
        ['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n',
        capture_output=True, text=True, timeout=25,
        env=dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never'))
    values = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    if result.returncode or not values.get('password'):
        raise RuntimeError('GitHub credential is unavailable')
    return values['password']


TOKEN = None


def api(path='', method='GET', data=None, optional=False):
    url = path if path.startswith('https://api.github.com/') else BASE + ('/' + path if path else '')
    headers = {'User-Agent': 'PickupOfficialWebsite', 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28', 'Authorization': 'Bearer ' + TOKEN}
    body = None if data is None else json.dumps(data).encode('utf-8')
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            value = response.read()
            return json.loads(value) if value else None
    except urllib.error.HTTPError as error:
        if optional and error.code == 404:
            return None
        detail = json.loads(error.read()).get('message', 'GitHub request failed')
        raise RuntimeError(f'{method} {url}: HTTP {error.code}, {detail}') from None


def check():
    repo = api()
    head = api('git/ref/heads/main')['object']['sha']
    stable = api('contents/update.json?ref=' + head)
    local_stable = json.loads((WORKSPACE / 'update.json').read_text(encoding='utf-8'))
    if json.loads(base64.b64decode(stable['content'])) != local_stable:
        raise RuntimeError('Local and published stable manifests differ')
    workflow = api('contents/.github/workflows/deploy-pages.yml?ref=' + head, optional=True)
    pages = api('pages', optional=True)
    print(json.dumps({'repository': repo['full_name'], 'defaultBranch': repo['default_branch'],
                      'admin': repo['permissions'].get('admin'), 'head': head,
                      'stableVersion': local_stable['versionName'],
                      'pages': pages, 'workflowExists': bool(workflow)}, ensure_ascii=False))
    for action, tag in [('checkout', 'v6'), ('setup-node', 'v6'),
                        ('configure-pages', 'v5'), ('upload-pages-artifact', 'v4'), ('deploy-pages', 'v4')]:
        ref = api(f'https://api.github.com/repos/actions/{action}/git/ref/tags/{tag}')
        print(f'Action available: actions/{action}@{tag} ({ref["object"]["sha"][:12]})')
    return head, pages


def source_files():
    excluded = {'node_modules', 'dist', '.vite', '__pycache__'}
    paths = []
    for file in PROJECT.rglob('*'):
        if not file.is_file() or any(part in excluded for part in file.relative_to(PROJECT).parts):
            continue
        if file.suffix in {'.log', '.pyc'}:
            continue
        if 'qa' in file.relative_to(PROJECT).parts and file.name not in {
                'selected-reference.png', 'desktop-v1.jpg', 'desktop-final.jpg', 'mobile-final.jpg',
                'tablet-final.jpg', 'comparison-final.jpg', 'mobile-full.jpg', 'download-verification.json'}:
            continue
        if file.name.startswith('.env') or file.suffix in {'.jks', '.keystore', '.apk', '.key'}:
            raise RuntimeError('Unexpected sensitive/build artifact in website source')
        paths.append(file)
    paths.append(WORKSPACE / '.github/workflows/deploy-pages.yml')
    return sorted(paths)


def publish():
    qa = (PROJECT / 'design-qa.md').read_text(encoding='utf-8')
    if not qa.rstrip().endswith('final result: passed'):
        raise RuntimeError('Design QA must pass before publishing')
    if not (PROJECT / 'dist/client/index.html').exists():
        raise RuntimeError('Build the website before publishing')
    head, pages = check()
    previous = json.loads(AUDIT.read_text(encoding='utf-8')) if AUDIT.exists() else None
    if previous and previous.get('sourceCommit') != head:
        raise RuntimeError('Remote branch changed since website publication; inspect before updating')
    dispatch_needed = pages is None
    old_tree = api('git/commits/' + head)['tree']['sha']
    old_files = {f['path']: f['sha'] for f in api('git/trees/' + old_tree + '?recursive=1')['tree'] if f['type'] == 'blob'}
    changed = []
    hashes = {}
    for file in source_files():
        name = file.relative_to(WORKSPACE).as_posix()
        body = file.read_bytes()
        git_sha = hashlib.sha1(f'blob {len(body)}\0'.encode() + body).hexdigest()
        hashes[name] = hashlib.sha256(body).hexdigest()
        if old_files.get(name) == git_sha:
            continue
        blob = api('git/blobs', 'POST', {'content': base64.b64encode(body).decode(), 'encoding': 'base64'})
        changed.append({'path': name, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
    if changed:
        tree = api('git/trees', 'POST', {'base_tree': old_tree, 'tree': changed})
        commit = api('git/commits', 'POST', {'message': 'Add Shijianbu official website and GitHub Pages deployment',
                                           'tree': tree['sha'], 'parents': [head]})
        if api('git/ref/heads/main')['object']['sha'] != head:
            raise RuntimeError('Remote branch advanced; review and retry without force pushing')
        api('git/refs/heads/main', 'PATCH', {'sha': commit['sha'], 'force': False})
        published = commit['sha']
    else:
        published = head
    if pages is None:
        pages = api('pages', 'POST', {'build_type': 'workflow'})
    elif pages.get('build_type') != 'workflow':
        raise RuntimeError('Existing Pages publishing source differs; inspect before changing it')
    # Changed source triggers push automatically. Dispatch explicitly only for
    # initial Pages enablement or an unchanged source rebuild.
    if dispatch_needed or not changed:
        api('actions/workflows/deploy-pages.yml/dispatches', 'POST', {'ref': 'main'})
    record = {'success': False, 'phase': 'deployment_dispatched',
              'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'repository': REPO, 'previousHead': head, 'sourceCommit': published,
              'url': 'https://syczk301.github.io/pickup-assistant/',
              'changedFiles': [entry['path'] for entry in changed], 'sha256': hashes,
              'appStableManifestUnchanged': old_files.get('update.json') == api('contents/update.json?ref=' + published)['sha'],
              'appBetaManifestUnchanged': old_files.get('update-beta.json') == api('contents/update-beta.json?ref=' + published)['sha']}
    if previous:
        record['history'] = previous.get('history', []) + [
            {k: previous.get(k) for k in ('timestamp', 'sourceCommit', 'phase', 'success')}]
    AUDIT.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in record.items() if k != 'sha256'}, ensure_ascii=False))


def status():
    record = json.loads(AUDIT.read_text(encoding='utf-8')) if AUDIT.exists() else {}
    runs = api('actions/workflows/deploy-pages.yml/runs?per_page=6')['workflow_runs']
    values = [{k: run.get(k) for k in ('id', 'html_url', 'head_sha', 'event', 'status', 'conclusion')}
              for run in runs if not record.get('sourceCommit') or run['head_sha'] == record['sourceCommit']]
    print(json.dumps({'runs': values, 'pages': api('pages', optional=True)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['check', 'publish', 'status'])
    arguments = parser.parse_args()
    TOKEN = credentials()
    globals()[arguments.mode]()

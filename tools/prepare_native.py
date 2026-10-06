"""Restore matched native dependencies after accepting their individual licenses."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.run(list(args), check=True, capture_output=True, text=True).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path, help='QAIRT SDK root, 2.49.0.260730')
    parser.add_argument('--appbuilder', required=True, type=Path, help='Matched ARM64 libappbuilder.so (see native-dependencies.json)')
    parser.add_argument('--mnn-source', type=Path, help='Optional clean local checkout at the pinned MNN commit')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'tools/native-dependencies.json').read_text(encoding="utf-8"))
    plans = []
    for record in manifest['artifacts']:
        if record.get('appbuilder'):
            blob = args.appbuilder.read_bytes()
        elif record.get('maven_aar'):
            m = record['maven_aar']
            url = ('https://dl.google.com/dl/android/maven2/' + m['group'].replace('.', '/') + '/'
                   + m['artifact'] + '/' + m['version'] + '/' + m['artifact'] + '-' + m['version'] + '.aar')
            with urllib.request.urlopen(url, timeout=90) as response:
                data = response.read(50 * 1024 * 1024 + 1)
            if len(data) > 50 * 1024 * 1024:
                raise ValueError('Oversized AAR')
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                blob = archive.read(m['entry'])
        else:
            choices = [args.sdk / name for name in record['sdk_candidates']]
            source = next((path for path in choices if path.is_file()), None)
            if source is None:
                raise FileNotFoundError('SDK artifact missing: ' + record['destination'])
            blob = source.read_bytes()
        if len(blob) != record['bytes'] or hashlib.sha256(blob).hexdigest() != record['sha256']:
            raise ValueError('Wrong dependency version/hash: ' + record['destination'])
        target = (ROOT / record['destination']).resolve()
        target.relative_to(ROOT)
        plans.append((target, blob))
    mnn = ROOT / 'app/src/main/cpp/External/MNN'
    commit = manifest['mnn_commit']
    if args.mnn_source:
        if run('git', '-C', str(args.mnn_source), 'rev-parse', 'HEAD') != commit:
            raise ValueError('Local MNN checkout does not match pin')
        if run('git', '-C', str(args.mnn_source), 'status', '--porcelain'):
            raise ValueError('Local MNN checkout is modified')
        if not mnn.exists():
            shutil.copytree(args.mnn_source, mnn, ignore=shutil.ignore_patterns('.git'))
        (mnn / '.xyether-pinned-commit').write_text(commit)
    elif not mnn.exists():
        mnn.mkdir(parents=True)
        run('git', '-C', str(mnn), 'init')
        run('git', '-C', str(mnn), 'remote', 'add', 'origin', manifest['mnn_url'])
        run('git', '-C', str(mnn), 'fetch', '--depth=1', 'origin', commit)
        run('git', '-C', str(mnn), 'checkout', '--detach', 'FETCH_HEAD')
    elif (mnn / '.git').exists():
        if run('git', '-C', str(mnn), 'rev-parse', 'HEAD') != commit or run('git', '-C', str(mnn), 'status', '--porcelain'):
            raise ValueError('Existing MNN tree differs from pinned clean checkout')
    elif not (mnn / '.xyether-pinned-commit').is_file() or (mnn / '.xyether-pinned-commit').read_text(encoding="utf-8") != commit:
        raise ValueError('Existing MNN tree is unverified; move it aside before preparing dependencies')
    for target, blob in plans:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(blob)
    print('NATIVE_DEPENDENCIES_OK', len(plans), 'MNN_COMMIT', commit)


if __name__ == '__main__':
    main()

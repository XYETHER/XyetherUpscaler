"""Align and sign a release APK using a private config kept outside this repository."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unsigned', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--private-config', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError('Refusing to overwrite existing output')
    config = json.loads(args.private_config.read_text(encoding="utf-8"))
    env = os.environ.copy()
    env['XYETHER_STORE_PASSWORD'] = config['store_password']
    env['XYETHER_KEY_PASSWORD'] = config['key_password']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(suffix='.apk', prefix='xyether-aligned-')
    os.close(fd)
    aligned = Path(name)
    try:
        subprocess.run([str(args.build_tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign')),
                        '-f', '-p', '4', str(args.unsigned), str(aligned)], check=True)
        signer = args.build_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
        subprocess.run([str(signer), 'sign', '--ks', config['keystore'], '--ks-key-alias', config['alias'],
                        '--ks-pass', 'env:XYETHER_STORE_PASSWORD', '--key-pass', 'env:XYETHER_KEY_PASSWORD',
                        '--out', str(args.output), str(aligned)], env=env, check=True)
        subprocess.run([str(signer), 'verify', '--verbose', '--print-certs', str(args.output)], check=True)
    finally:
        aligned.unlink(missing_ok=True)


if __name__ == '__main__':
    main()

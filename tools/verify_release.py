"""Focused source/model/APK release checks; not a device inference test suite."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def validate_blob(blob, item):
    if len(blob) != item['bytes'] or digest(blob) != item['sha256']:
        raise ValueError('Artifact mismatch: ' + item['path'])


def check(root=ROOT, apk=None):
    manifest = json.loads((root / 'tools/model-assets.json').read_text(encoding="utf-8"))
    java = (root / 'app/src/main/java/com/xyether/upscaler/MainActivity.java').read_text(encoding='utf-8')
    specs = {}
    pattern = r'private static final ModelSpec (\w+) = fallbackBatchModel\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*([\d.]+)f,\s*(-?\d+),\s*(\d+)\);'
    for name, label, stem, mnn, scale, offset, batch in re.findall(pattern, java):
        specs[name] = {'label': label, 'stem': stem, 'mnn': mnn, 'quant_scale': float(scale), 'quant_offset': int(offset), 'batch': int(batch)}
    match = re.search(r'BUILTIN_MODELS = Arrays.asList\((.*?)\);', java, re.S)
    if not match:
        raise ValueError('Missing visible catalog')
    visible = re.findall(r'\b[A-Z][A-Z0-9_]+\b', match.group(1))
    expected = manifest['catalog']
    if visible != [entry['id'] for entry in expected]:
        raise ValueError('Visible catalog differs from release manifest')
    for entry in expected:
        if specs.get(entry['id']) != {k: v for k, v in entry.items() if k != 'id'}:
            raise ValueError('Model contract mapping changed: ' + entry['id'])
    for item in manifest['artifacts']:
        validate_blob((root / item['path']).read_bytes(), item)
    dlc = list((root / 'app/src/main/assets/models').glob('*.dlc'))
    mnn = list((root / 'app/src/main/assets/mnn').glob('*.mnn'))
    if len(dlc) != 16 or len(mnn) != 8:
        raise ValueError('Unexpected model asset count')
    if apk:
        with zipfile.ZipFile(apk) as archive:
            if archive.testzip():
                raise ValueError('Corrupt APK ZIP')
            for item in manifest['artifacts']:
                validate_blob(archive.read(item['path'].removeprefix('app/src/main/')), item)
            abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
            if abis != {'arm64-v8a'}:
                raise ValueError('Unexpected APK ABI set')
            if 'lib/arm64-v8a/libxyether_backend.so' not in archive.namelist():
                raise ValueError('Missing native engine')
    print('SOURCE_MODEL_CONTRACTS_OK PROFILES=8 DLC=16 MNN=8' + (' APK_MODELS_MATCH_SOURCE' if apk else ''))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    check(apk=parser.parse_args().apk)

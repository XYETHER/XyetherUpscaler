"""Conservative upload hygiene check. Not a comprehensive secret detector."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
issues = []
count = 0
size = 0
for path in root.rglob('*'):
    if not path.is_file() or any(part in {'.git', '__pycache__'} for part in path.parts):
        continue
    relative = path.relative_to(root).as_posix()
    count += 1
    size += path.stat().st_size
    if path.stat().st_size >= 100 * 1024 * 1024:
        issues.append((relative, 'GitHub large-file limit'))
    if path.suffix.lower() in {'.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab', '.so', '.log'} or path.name in {'.env', 'local.properties', 'signing-private.json'}:
        issues.append((relative, 'private/generated/vendor file'))
    if any(part in {'.gradle', '.idea', '.cxx', '.hermes', 'build'} for part in path.relative_to(root).parts):
        issues.append((relative, 'local state'))
    if path.suffix.lower() in {'.java', '.cpp', '.hpp', '.py', '.gradle', '.md', '.txt', '.properties', '.toml', '.json', '.yml', '.yaml'}:
        text = path.read_text(encoding='utf-8', errors='replace')
        normalized = text.replace(chr(92), '/')
        if 'C:/Users/HAMDAN/' in normalized or 'E:/temp/' in normalized or 'D:/downloads/' in normalized:
            # This auditor itself uses these exact markers without referencing local files.
            if path.name != 'audit_upload.py':
                issues.append((relative, 'personal absolute path'))
        for label, pattern in [('private-key', r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'), ('github-token', r'gh[pousr]_[A-Za-z0-9]{30,}'), ('aws-key', r'AKIA[A-Z0-9]{16}')]:
            if re.search(pattern, text):
                issues.append((relative, label))
print('UPLOAD_FILES', count, 'BYTES', size)
print('HYGIENE_ISSUES_FILENAMES_ONLY', issues)
if issues:
    sys.exit(1)
print('UPLOAD_HYGIENE_OK')

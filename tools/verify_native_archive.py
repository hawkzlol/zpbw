"""Verify locally generated Loom archives by exact contents, independent of ZIP metadata."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

EXPECTED = {
    '26.2': ('minecraft-merged-043a8b3edf-26.2.jar', 'b3e62675792b62db3b246dfa9b82f961ab8322cfefe2016cb8b839d1e472a079'),
    '26.3': ('minecraft-merged-7e9a32a5b8-26.3.jar', 'a961f8594b91cede0fad7292b1b15bc5e767a50552212556e475c98c286d1853'),
}


def verify(path, native):
    expected_name, expected_digest = EXPECTED[native]
    if path.name != expected_name:
        raise ValueError('Wrong generated native archive')
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)) or len(names) > 100_000:
            raise ValueError('Duplicate or excessive native entries')
        if sum(e.file_size for e in entries) > 512 * 1024 * 1024 or any(e.file_size > 64 * 1024 * 1024 for e in entries):
            raise ValueError('Native archive size bound')
        if json.loads(archive.read('version.json')).get('id') != native:
            raise ValueError('Wrong native version')
        rows = (name + ' ' + hashlib.sha256(archive.read(name)).hexdigest() + '\n' for name in sorted(names))
        actual = hashlib.sha256(''.join(rows).encode()).hexdigest()
        if actual != expected_digest:
            raise ValueError('Native archive contents differ from audited released inputs')
    return actual


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', choices=EXPECTED, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    owned = root / ('versions/mc' + args.native.replace('.', '_')) / '.gradle/loom-cache/minecraftMaven'
    if not args.jar.resolve(strict=True).is_relative_to(owned.resolve()):
        raise ValueError('Generated archive must belong to the selected native Loom cache')
    print('Verified native content ' + args.native + ': ' + verify(args.jar, args.native))

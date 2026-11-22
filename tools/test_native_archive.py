import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from verify_native_archive import EXPECTED, verify


class NativeArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / EXPECTED['26.3'][0]
        self.contents = {'version.json': b'{"id":"26.3"}', 'Game.class': b'original class', 'data.json': b'original data'}
        digest = hashlib.sha256(''.join(k+' '+hashlib.sha256(v).hexdigest()+'\n' for k,v in sorted(self.contents.items())).encode()).hexdigest()
        self.mock = patch.dict(EXPECTED, {'26.3': (self.path.name, digest)})
        self.mock.start(); self.addCleanup(self.mock.stop)

    def write(self, compression=zipfile.ZIP_STORED):
        with zipfile.ZipFile(self.path, 'w', compression=compression) as archive:
            for name, data in self.contents.items(): archive.writestr(name, data)

    def test_original_contents(self):
        self.write(); verify(self.path, '26.3')

    def test_container_changes_preserve_exact_content_identity(self):
        self.write(); original = self.path.read_bytes(); first = verify(self.path, '26.3')
        self.write(zipfile.ZIP_DEFLATED)
        self.assertNotEqual(original, self.path.read_bytes())
        self.assertEqual(first, verify(self.path, '26.3'))

    def test_class_tampering_rejected(self):
        self.contents['Game.class'] = b'changed'; self.write()
        with self.assertRaises(ValueError): verify(self.path, '26.3')

    def test_resource_tampering_rejected(self):
        self.contents['data.json'] = b'changed'; self.write()
        with self.assertRaises(ValueError): verify(self.path, '26.3')

    def test_added_entry_rejected(self):
        self.contents['Extra.class'] = b'extra'; self.write()
        with self.assertRaises(ValueError): verify(self.path, '26.3')

    def test_wrong_version_rejected(self):
        self.contents['version.json'] = b'{"id":"26.2"}'; self.write()
        with self.assertRaises(ValueError): verify(self.path, '26.3')

    def test_duplicate_entry_rejected(self):
        self.write()
        with zipfile.ZipFile(self.path, 'a') as archive: archive.writestr('Game.class', b'original class')
        with self.assertRaises(ValueError): verify(self.path, '26.3')

"""Release-verifier regression checks. No hardware inference claim."""
import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_release import check, validate_blob


class ReleaseVerifierTests(unittest.TestCase):
    def test_current_source_catalog_and_model_hashes(self):
        check()

    def test_shared_jni_guard_precedes_engine_dispatch(self):
        root = Path(__file__).resolve().parents[1]
        cpp = (root / 'app/src/main/cpp/qnn_upscaler.cpp').read_text(encoding='utf-8')
        entry = cpp.split('Java_com_xyether_upscaler_MainActivity_nativeUpscaleRgb(', 1)[1]
        self.assertLess(entry.index('width <= 0 || height <= 0 || width > 8192 || height > 8192'), entry.index('upscaleLocked('))
        self.assertLess(entry.index('if (!inputBuffer || !outputBuffer)'), entry.index('GetDirectBufferAddress'))
        java = (root / 'app/src/main/java/com/xyether/upscaler/MainActivity.java').read_text(encoding='utf-8')
        self.assertIn('if (!saveInProgress.get()) recycleComparison.run();', java)
        self.assertIn('if (comparisonDismissed.get()) recycleComparison.run();', java)

    def test_checksum_rejects_tampered_same_length_file(self):
        item = {'path': 'test.model', 'bytes': 4, 'sha256': hashlib.sha256(b'good').hexdigest()}
        validate_blob(b'good', item)
        with self.assertRaises(ValueError):
            validate_blob(b'evil', item)

    def test_size_rejects_truncated_file(self):
        item = {'path': 'test.model', 'bytes': 4, 'sha256': hashlib.sha256(b'good').hexdigest()}
        with self.assertRaises(ValueError):
            validate_blob(b'goo', item)


if __name__ == '__main__':
    unittest.main()

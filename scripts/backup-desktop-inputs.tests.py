import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("desktop_input_backup", Path(__file__).with_name("backup-desktop-inputs.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class InputBackupTests(unittest.TestCase):
    def create_database(self, path, blob_id=None):
        connection = sqlite3.connect(path)
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("CREATE TABLE document (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        connection.execute("CREATE TABLE blob (id TEXT PRIMARY KEY,data BLOB NOT NULL)")
        data = b"unsent attachment bytes"
        actual_id = hashlib.sha256(data).hexdigest()
        value = {"prompt": [{"type": "text", "content": "unsent input\nsecond line"}, {"type": "image", "blob": {"id": blob_id or actual_id}}], "cursor": 12, "unknown": {"keep": True}}
        connection.execute("INSERT INTO document VALUES (?,?)", ("workspace:session:prompt", json.dumps(value)))
        connection.execute("INSERT INTO blob VALUES (?,?)", (actual_id, data))
        connection.commit()
        return connection, value

    def test_online_wal_snapshot_keeps_text_context_and_attachment(self):
        with tempfile.TemporaryDirectory(prefix="oc-input-backup-") as directory:
            root = Path(directory)
            database, value = self.create_database(root / "source.sqlite")
            with database:
                witness = root / "visible-input.txt"
                witness.write_text("unsent input\nsecond line", encoding="utf-8")
                result = module.backup_inputs(root / "source.sqlite", root / "backup", witness)
                self.assertTrue(result["visible_input_verified"])
                self.assertEqual(result["attachment_blobs"], 1)
                self.assertEqual(json.loads((root / "backup" / "draft-documents.json").read_text())[0]["value"], value)
                self.assertEqual(database.execute("SELECT count(*) FROM document").fetchone(), (1,))
            database.close()

    def test_visible_memory_text_missing_from_disk_cannot_pass(self):
        with tempfile.TemporaryDirectory(prefix="oc-input-backup-") as directory:
            root = Path(directory)
            database, _ = self.create_database(root / "source.sqlite")
            witness = root / "visible-input.txt"
            witness.write_text("new memory-only input", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Visible input is absent"):
                module.backup_inputs(root / "source.sqlite", root / "backup", witness)
            self.assertFalse((root / "backup" / "input-backup-verification.json").exists())
            database.close()

    def test_missing_attachment_cannot_pass(self):
        with tempfile.TemporaryDirectory(prefix="oc-input-backup-") as directory:
            root = Path(directory)
            database, _ = self.create_database(root / "source.sqlite", "missing-blob")
            with self.assertRaisesRegex(ValueError, "missing attachment references"):
                module.backup_inputs(root / "source.sqlite", root / "backup")
            database.close()

    def test_private_drafts_cannot_be_written_to_repository(self):
        with self.assertRaisesRegex(ValueError, "outside the repository"):
            module.backup_inputs(Path("does-not-exist"), Path(__file__).resolve().parent / "private-input-backup")


if __name__ == "__main__":
    unittest.main()

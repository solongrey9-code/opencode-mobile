"""Make a consistent, read-only snapshot of Desktop inputs, including WAL/blobs.

Output contains private draft content. Keep it outside the repository.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3


def backup_inputs(source: Path, output: Path, expected_text_file: Path | None = None):
    repository = Path(__file__).resolve().parent.parent
    output = output.resolve()
    if output == repository or repository in output.parents:
        raise ValueError("Private input backups must be outside the repository")
    if not source.is_file():
        raise ValueError("Desktop draft database is missing; input safety is unverified")
    output.mkdir(parents=True, exist_ok=True)
    snapshot = output / "drafts.sqlite"
    if snapshot.exists():
        raise ValueError("Choose a fresh output directory; never overwrite an input backup")
    with sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True) as original:
        with sqlite3.connect(snapshot) as saved:
            original.backup(saved, pages=64, sleep=0.01)
            if saved.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                raise ValueError("Draft database snapshot failed its integrity check")
            documents = saved.execute("SELECT key,value FROM document ORDER BY key").fetchall()
            blobs = dict(saved.execute("SELECT id,data FROM blob").fetchall())
    for blob_id, data in blobs.items():
        if hashlib.sha256(data).hexdigest() != blob_id:
            raise ValueError("A draft attachment failed its SHA-256 integrity check")

    decoded = []
    texts = []
    missing_blobs = set()

    def inspect(value):
        if isinstance(value, list):
            for item in value:
                inspect(item)
        elif isinstance(value, dict):
            if value.get("type") == "text" and isinstance(value.get("content"), str):
                texts.append(value["content"])
            blob = value.get("blob")
            if isinstance(blob, dict) and isinstance(blob.get("id"), str):
                if blob["id"] not in blobs:
                    missing_blobs.add(blob["id"])
            for item in value.values():
                inspect(item)

    for key, raw in documents:
        value = json.loads(raw)
        inspect(value)
        decoded.append({"key": key, "value": value})
    if missing_blobs:
        raise ValueError("The snapshot has missing attachment references; do not close Desktop")
    expected_verified = None
    if expected_text_file:
        expected_text = expected_text_file.read_text(encoding="utf-8")
        if not expected_text:
            raise ValueError("The visible-input witness must contain nonempty exact text")
        expected_verified = expected_text in texts
        if not expected_verified:
            raise ValueError("Visible input is absent from the saved drafts; do not close Desktop")
    export = output / "draft-documents.json"
    export.write_text(json.dumps(decoded, ensure_ascii=False, indent=2), encoding="utf-8")
    # Verify the human-readable recovery copy without printing any draft contents.
    if json.loads(export.read_text(encoding="utf-8")) != decoded:
        raise ValueError("Draft document export failed its read-back check")
    result = {
        "snapshot": str(snapshot),
        "sha256": hashlib.sha256(snapshot.read_bytes()).hexdigest(),
        "documents": len(documents),
        "nonempty_text_parts": sum(bool(text) for text in texts),
        "attachment_blobs": len(blobs),
        "visible_input_verified": expected_verified,
        "source_modified": False,
    }
    (output / "input-backup-verification.json").write_text(
        json.dumps(result, indent=2), encoding="utf-8"
    )
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-text-file", type=Path)
    parser.add_argument("--source", type=Path, default=Path(os.environ["APPDATA"]) / "ai.opencode.desktop" / "drafts.sqlite")
    args = parser.parse_args()
    try:
        print(json.dumps(backup_inputs(args.source, args.output, args.expected_text_file)))
    except (ValueError, sqlite3.Error, OSError) as error:
        # Do not print SQLite rows, renderer content or native errors containing data.
        print(json.dumps({"verified": False, "reason": str(error) if isinstance(error, ValueError) else type(error).__name__}))
        raise SystemExit(1)

import threading
import time
from pathlib import Path
from unittest.mock import MagicMock

from opik.runner.file_watcher import FileWatcher


class TestFileWatcher:
    def test_init__stores_config(self, tmp_path: Path) -> None:
        cb = MagicMock()
        watcher = FileWatcher(tmp_path, cb, extensions={".py"}, debounce_seconds=0.5)
        assert watcher._repo_root == tmp_path
        assert watcher._extensions == {".py"}
        assert watcher._debounce_seconds == 0.5

    def test_run__py_change__triggers_callback(self, tmp_path: Path) -> None:
        cb = MagicMock()
        watcher = FileWatcher(tmp_path, cb, debounce_seconds=0.1)
        shutdown = threading.Event()

        t = threading.Thread(target=watcher.run, args=(shutdown,), daemon=True)
        t.start()

        time.sleep(0.15)
        (tmp_path / "test.py").write_text("hello")
        time.sleep(0.3)

        shutdown.set()
        t.join(timeout=5)

        assert cb.call_count >= 1
        changed_paths = cb.call_args[0][0]
        assert any(p.name == "test.py" for p in changed_paths)

    def test_run__txt_change__ignored(self, tmp_path: Path) -> None:
        cb = MagicMock()
        # Create a subdir so watchfiles has something to watch without triggering
        subdir = tmp_path / "src"
        subdir.mkdir()

        watcher = FileWatcher(subdir, cb, debounce_seconds=0.1)
        shutdown = threading.Event()

        t = threading.Thread(target=watcher.run, args=(shutdown,), daemon=True)
        t.start()

        time.sleep(0.15)
        (subdir / "notes.txt").write_text("ignored")
        time.sleep(0.3)

        shutdown.set()
        t.join(timeout=5)

        assert cb.call_count == 0

    def test_run__shutdown__stops(self, tmp_path: Path) -> None:
        cb = MagicMock()
        watcher = FileWatcher(tmp_path, cb)
        shutdown = threading.Event()

        t = threading.Thread(target=watcher.run, args=(shutdown,), daemon=True)
        t.start()

        time.sleep(0.1)
        shutdown.set()
        t.join(timeout=5)
        assert not t.is_alive()

    def test_run__callback_error__does_not_crash(self, tmp_path: Path) -> None:
        cb = MagicMock(side_effect=RuntimeError("boom"))
        watcher = FileWatcher(tmp_path, cb, debounce_seconds=0.1)
        shutdown = threading.Event()

        t = threading.Thread(target=watcher.run, args=(shutdown,), daemon=True)
        t.start()

        time.sleep(0.15)
        (tmp_path / "test.py").write_text("trigger")
        time.sleep(0.3)

        shutdown.set()
        t.join(timeout=5)
        assert not t.is_alive()

import os
import threading
import time
from pathlib import Path

import pytest

from opik.runner.bridge_handlers import (
    CommandError,
    FileMutationQueue,
    StubHandler,
)


class TestStubHandler:
    def test_raises_not_implemented(self) -> None:
        handler = StubHandler()
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "test.py"}, timeout=30.0)
        assert exc_info.value.code == "not_implemented"

    def test_command_error_fields(self) -> None:
        err = CommandError("file_not_found", "No such file: test.py")
        assert err.code == "file_not_found"
        assert err.message == "No such file: test.py"
        assert "file_not_found" in str(err)


class TestFileMutationQueue:
    def test_same_file__serialized(self, tmp_path: Path) -> None:
        queue = FileMutationQueue()
        f = tmp_path / "a.py"
        f.write_text("")

        order: list[int] = []

        def writer(n: int) -> None:
            with queue.lock(f):
                order.append(n)
                time.sleep(0.1)

        t1 = threading.Thread(target=writer, args=(1,))
        t2 = threading.Thread(target=writer, args=(2,))
        t1.start()
        time.sleep(0.02)
        t2.start()
        t1.join()
        t2.join()

        assert order == [1, 2]

    def test_different_files__parallel(self, tmp_path: Path) -> None:
        queue = FileMutationQueue()
        f1 = tmp_path / "a.py"
        f2 = tmp_path / "b.py"
        f1.write_text("")
        f2.write_text("")

        start_times: dict[int, float] = {}

        def writer(f: Path, n: int) -> None:
            with queue.lock(f):
                start_times[n] = time.monotonic()
                time.sleep(0.1)

        t1 = threading.Thread(target=writer, args=(f1, 1))
        t2 = threading.Thread(target=writer, args=(f2, 2))
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        assert abs(start_times[1] - start_times[2]) < 0.05

    def test_symlink__resolves_to_same_lock(self, tmp_path: Path) -> None:
        queue = FileMutationQueue()
        real = tmp_path / "real.py"
        real.write_text("")
        link = tmp_path / "link.py"
        link.symlink_to(real)

        lock1 = queue.lock(real)
        lock2 = queue.lock(link)
        assert lock1 is lock2

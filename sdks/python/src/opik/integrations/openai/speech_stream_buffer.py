import tempfile
from pathlib import Path
from typing import Optional


class SpeechStreamBuffer:
    def __init__(self, max_size: Optional[int] = None) -> None:
        self._buf = bytearray()
        self._max_size = max_size
        self._overflowed = False

    def add(self, chunk: bytes) -> None:
        if self._overflowed:
            return
        self._buf.extend(chunk)
        if self._max_size is not None and len(self._buf) > self._max_size:
            self._overflowed = True
            self._buf.clear()

    def should_attach(self) -> bool:
        return (not self._overflowed) and len(self._buf) > 0

    def flush_to_tempfile(self, suffix: str) -> Optional[Path]:
        if not self.should_attach():
            return None
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp.write(self._buf)
            path = Path(tmp.name)
        self.clear()
        return path

    def clear(self) -> None:
        self._buf.clear()
        self._overflowed = False

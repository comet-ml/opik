"""Wraps stdout/stderr to prefix each line with a gradient-colored ' ┃'."""

import io
import sys
import typing

# Orange → Red gradient endpoints (matches banner)
_R_START, _G_START, _B_START = 0xF5, 0xA6, 0x23
_R_END, _G_END, _B_END = 0xE0, 0x3E, 0x2D

# Number of lines for one full gradient sweep
_CYCLE_LENGTH = 20


def _color_for_line(n: int) -> str:
    t = (n % (2 * _CYCLE_LENGTH)) / _CYCLE_LENGTH
    if t > 1:
        t = 2 - t
    r = int(_R_START + (_R_END - _R_START) * t)
    g = int(_G_START + (_G_END - _G_START) * t)
    b = int(_B_START + (_B_END - _B_START) * t)
    return f"\033[38;2;{r};{g};{b}m"


_RESET = "\033[0m"

_shared_line_count = 0


class PrefixedStream(io.TextIOBase):
    encoding: str = "utf-8"

    def __init__(self, stream: typing.TextIO) -> None:
        self._stream = stream
        self._at_line_start = True
        self.encoding = getattr(stream, "encoding", "utf-8")

    def write(self, s: str) -> int:
        global _shared_line_count
        parts = s.split("\n")
        out: list[str] = []
        for i, part in enumerate(parts):
            if i > 0:
                out.append("\n")
                self._at_line_start = True
                _shared_line_count += 1
            if part and self._at_line_start:
                color = _color_for_line(_shared_line_count)
                out.append(f"{color} \u2503  {_RESET}")
                self._at_line_start = False
            out.append(part)
        result = "".join(out)
        return self._stream.write(result)

    def flush(self) -> None:
        self._stream.flush()

    def isatty(self) -> bool:
        return self._stream.isatty()

    def fileno(self) -> int:
        return self._stream.fileno()


def install() -> None:
    if not hasattr(sys.stdout, "isatty") or not sys.stdout.isatty():
        return
    sys.stdout = PrefixedStream(sys.stdout)  # type: ignore[assignment]
    sys.stderr = PrefixedStream(sys.stderr)  # type: ignore[assignment]

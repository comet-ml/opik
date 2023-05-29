from typing import TYPE_CHECKING, List

if TYPE_CHECKING:  # pragma: no cover
    from . import span

class Context:
    def __init__(self):
        self._stack: List["span.Span"] = []

    def add(self, span: "span.Span") -> None:
        self._stack.append(span)

    def pop(self) -> None:
        if len(self._stack) > 0:
            self._stack.pop()

    def current(self) -> List[int]:
        return [span.id for span in self._stack]

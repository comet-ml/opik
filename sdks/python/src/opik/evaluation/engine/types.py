from typing import Protocol, TypeVar

T = TypeVar("T", covariant=True)


class EvaluationTask(Protocol[T]):
    def __call__(self) -> T:
        pass

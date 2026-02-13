from typing import Protocol, TypeVar

T = TypeVar("T", covariant=True)


class EvaluationTask(Protocol[T]):
    def __call__(self) -> T:
        pass


# Reserved key for item-level evaluation config stored in dataset item content.
# Contains: {"evaluators": [...], "execution_policy": {...}}
EVALUATION_CONFIG_KEY = "__evaluation_config__"

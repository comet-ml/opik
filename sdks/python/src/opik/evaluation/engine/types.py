from typing import Protocol, TypeVar

T = TypeVar("T", covariant=True)


class EvaluationTask(Protocol[T]):
    def __call__(self) -> T:
        pass


# Reserved key for evaluation suite metadata stored in dataset item content.
# This will become proper backend fields on Dataset and DatasetItem once
# OPIK-4222/4223 are implemented. Contains: {"evaluators": [...], "execution_policy": {...}}
EVALUATION_CONFIG_KEY = "__evaluation_config__"

# Reserved ID for the special dataset item that stores suite-level configuration.
# This item is not a test case but holds suite-level evaluators and execution_policy.
SUITE_CONFIG_ITEM_ID = "__suite_config__"

from typing import List, Any
from .. import messages


class BatchesCollector:
    def __init__(self, batch_builders: List[Any]):
        self._batch_builders = batch_builders
        pass

    def can_be_batched(self, message: messages.BaseMessage) -> bool:
        return any(
            [
                isinstance(message, batch_builder.supported_types)
                for batch_builder in self._batch_builders
            ]
        )

    def add(self, message: messages.BaseMessage) -> bool:
        pass

    def flush():
        pass

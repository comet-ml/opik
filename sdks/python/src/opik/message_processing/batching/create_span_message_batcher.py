from . import base_batcher
from .. import messages


class CreateSpanMessageBatcher(base_batcher.BaseBatcher):
    def _create_batch_from_accumulated_messages(
        self,
    ) -> messages.CreateSpansBatchMessage:
        assert isinstance(self._accumulated_messages, messages.CreateSpanMessage)
        return messages.CreateSpansBatchMessage(batch=self._accumulated_messages)  # type: ignore

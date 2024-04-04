
from typing import Union

from ... import config
from . import offline_message_processor, online_message_processor

MESSAGE_PROCESSOR: Union[
    offline_message_processor.OfflineMessageProcessor,
    online_message_processor.OnlineMessageProcessor,
]

if config.offline_enabled():
    MESSAGE_PROCESSOR = offline_message_processor.OfflineMessageProcessor(
        offline_directory=config.offline_directory(),
        file_usage_duration=config.offline_batch_duration_seconds(),
    )
else:
    MESSAGE_PROCESSOR = online_message_processor.OnlineMessageProcessor()

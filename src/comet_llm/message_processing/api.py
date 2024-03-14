from . import offline_message_processor, online_message_processor

from .. import config


OFFLINE_MESSAGE_PROCESSOR = offline_message_processor.OfflineMessageProcessor(
    offline_directory=config.offline_folder_path(),
    batch_duration_seconds=config.offline_batch_duration_seconds()
)

ONLINE_MESSAGE_PROCESSOR = online_message_processor.OnlineMessageProcessor()
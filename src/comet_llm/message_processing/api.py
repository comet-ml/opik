from .. import config
from . import offline_message_processor, online_message_processor

if config.offline_enabled():
    MESSAGE_PROCESSOR = offline_message_processor.OfflineMessageProcessor(
        offline_directory=config.offline_folder_path(),
        batch_duration_seconds=config.offline_batch_duration_seconds(),
    )
else:
    MESSAGE_PROCESSOR = online_message_processor.OnlineMessageProcessor()

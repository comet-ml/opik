"""
Type annotations for botocore.compress module.

Copyright 2025 Vlad Emelianov
"""

import io
from gzip import GzipFile as GzipFile
from logging import Logger
from typing import Any, Callable, Mapping

from botocore.compat import urlencode as urlencode
from botocore.config import Config
from botocore.model import OperationModel
from botocore.utils import determine_content_length as determine_content_length

logger: Logger = ...

def maybe_compress_request(
    config: Config, request_dict: Mapping[str, Any], operation_model: OperationModel
) -> None: ...

COMPRESSION_MAPPING: dict[str, Callable[[io.BytesIO], io.BytesIO]] = ...

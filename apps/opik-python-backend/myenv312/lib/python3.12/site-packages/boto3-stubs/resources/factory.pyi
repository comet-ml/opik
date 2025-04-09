"""
Type annotations for boto3.resources.factory module.

Copyright 2024 Vlad Emelianov
"""

import logging
from typing import Any

from boto3.resources.base import ServiceResource
from boto3.utils import ServiceContext
from botocore.hooks import BaseEventHooks

logger: logging.Logger

class ResourceFactory:
    def __init__(self, emitter: BaseEventHooks) -> None: ...
    def load_from_definition(
        self,
        resource_name: str,
        single_resource_json_definition: dict[str, Any],
        service_context: ServiceContext,
    ) -> type[ServiceResource]: ...

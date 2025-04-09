"""
Type annotations for boto3.ec2.deletetags module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any

from botocore.hooks import BaseEventHooks

def inject_delete_tags(event_emitter: BaseEventHooks, **kwargs: Any) -> None: ...
def delete_tags(self: Any, **kwargs: Any) -> None: ...

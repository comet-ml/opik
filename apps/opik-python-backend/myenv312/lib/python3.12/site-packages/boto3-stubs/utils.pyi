"""
Type annotations for boto3.utils module.

Copyright 2024 Vlad Emelianov
"""

from typing import Any

from botocore.model import ServiceModel
from botocore.session import Session
from botocore.waiter import Waiter, WaiterModel

class ServiceContext:
    def __init__(
        self,
        service_name: str,
        service_model: ServiceModel,
        service_waiter_model: WaiterModel | None,
        resource_json_definitions: dict[str, Any],
    ) -> None: ...

def import_module(name: str) -> Any: ...
def lazy_call(full_name: str, **kwargs: Any) -> Any: ...
def inject_attribute(class_attributes: dict[str, Any], name: str, value: Any) -> None: ...

class LazyLoadedWaiterModel:
    def __init__(self, bc_session: Session, service_name: str, api_version: str) -> None: ...
    def get_waiter(self, waiter_name: str) -> Waiter: ...

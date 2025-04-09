"""
Main interface for bedrock-runtime service.

[Documentation](https://youtype.github.io/boto3_stubs_docs/mypy_boto3_bedrock_runtime/)

Copyright 2025 Vlad Emelianov

Usage::

    ```python
    from boto3.session import Session
    from mypy_boto3_bedrock_runtime import (
        BedrockRuntimeClient,
        Client,
        ListAsyncInvokesPaginator,
    )

    session = Session()
    client: BedrockRuntimeClient = session.client("bedrock-runtime")

    list_async_invokes_paginator: ListAsyncInvokesPaginator = client.get_paginator("list_async_invokes")
    ```
"""

from .client import BedrockRuntimeClient
from .paginator import ListAsyncInvokesPaginator

Client = BedrockRuntimeClient

__all__ = ("BedrockRuntimeClient", "Client", "ListAsyncInvokesPaginator")

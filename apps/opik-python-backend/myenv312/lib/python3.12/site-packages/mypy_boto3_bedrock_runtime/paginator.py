"""
Type annotations for bedrock-runtime service client paginators.

[Documentation](https://youtype.github.io/boto3_stubs_docs/mypy_boto3_bedrock_runtime/paginators/)

Copyright 2025 Vlad Emelianov

Usage::

    ```python
    from boto3.session import Session

    from mypy_boto3_bedrock_runtime.client import BedrockRuntimeClient
    from mypy_boto3_bedrock_runtime.paginator import (
        ListAsyncInvokesPaginator,
    )

    session = Session()
    client: BedrockRuntimeClient = session.client("bedrock-runtime")

    list_async_invokes_paginator: ListAsyncInvokesPaginator = client.get_paginator("list_async_invokes")
    ```
"""

from __future__ import annotations

import sys
from typing import TYPE_CHECKING

from botocore.paginate import PageIterator, Paginator

from .type_defs import ListAsyncInvokesRequestPaginateTypeDef, ListAsyncInvokesResponseTypeDef

if sys.version_info >= (3, 12):
    from typing import Unpack
else:
    from typing_extensions import Unpack


__all__ = ("ListAsyncInvokesPaginator",)


if TYPE_CHECKING:
    _ListAsyncInvokesPaginatorBase = Paginator[ListAsyncInvokesResponseTypeDef]
else:
    _ListAsyncInvokesPaginatorBase = Paginator  # type: ignore[assignment]


class ListAsyncInvokesPaginator(_ListAsyncInvokesPaginatorBase):
    """
    [Show boto3 documentation](https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/bedrock-runtime/paginator/ListAsyncInvokes.html#BedrockRuntime.Paginator.ListAsyncInvokes)
    [Show boto3-stubs documentation](https://youtype.github.io/boto3_stubs_docs/mypy_boto3_bedrock_runtime/paginators/#listasyncinvokespaginator)
    """

    def paginate(  # type: ignore[override]
        self, **kwargs: Unpack[ListAsyncInvokesRequestPaginateTypeDef]
    ) -> PageIterator[ListAsyncInvokesResponseTypeDef]:
        """
        [Show boto3 documentation](https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/bedrock-runtime/paginator/ListAsyncInvokes.html#BedrockRuntime.Paginator.ListAsyncInvokes.paginate)
        [Show boto3-stubs documentation](https://youtype.github.io/boto3_stubs_docs/mypy_boto3_bedrock_runtime/paginators/#listasyncinvokespaginator)
        """

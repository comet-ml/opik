from __future__ import annotations

from typing import Any, Mapping, Optional, Protocol, overload

import openai as _openai
from opik.types import DistributedTraceHeadersDict

__all__ = ["track_openai"]


class _SupportsResponses(Protocol):
    def create(
        self,
        *args: Any,
        opik_args: Optional[Mapping[str, Any]] = ...,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = ...,
        **kwargs: Any,
    ) -> Any: ...

    def parse(
        self,
        *args: Any,
        opik_args: Optional[Mapping[str, Any]] = ...,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = ...,
        **kwargs: Any,
    ) -> Any: ...


class _SupportsChatCompletions(Protocol):
    def create(
        self,
        *args: Any,
        opik_args: Optional[Mapping[str, Any]] = ...,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = ...,
        **kwargs: Any,
    ) -> Any: ...

    def parse(
        self,
        *args: Any,
        opik_args: Optional[Mapping[str, Any]] = ...,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = ...,
        **kwargs: Any,
    ) -> Any: ...

    def stream(
        self,
        *args: Any,
        opik_args: Optional[Mapping[str, Any]] = ...,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = ...,
        **kwargs: Any,
    ) -> Any: ...


class _SupportsChat(Protocol):
    completions: _SupportsChatCompletions


class _SupportsBetaChat(Protocol):
    completions: _SupportsChatCompletions


class _SupportsBeta(Protocol):
    chat: _SupportsBetaChat


class _OpenAIWithOpikArgs(Protocol):
    responses: _SupportsResponses
    chat: _SupportsChat
    beta: _SupportsBeta


@overload
def track_openai(
    openai_client: _openai.OpenAI, project_name: Optional[str] = ...
) -> _OpenAIWithOpikArgs: ...


@overload
def track_openai(
    openai_client: _openai.AsyncOpenAI, project_name: Optional[str] = ...
) -> _OpenAIWithOpikArgs: ...


def track_openai(
    openai_client: Any, project_name: Optional[str] = ...
) -> _OpenAIWithOpikArgs: ...



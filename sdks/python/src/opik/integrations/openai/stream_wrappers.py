import logging
from typing import Iterator, AsyncIterator, Any, List, Optional, Callable
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers
from openai.types.chat import chat_completion_chunk
import openai

LOGGER = logging.getLogger(__name__)


def wrap_sync_stream(
    generator: openai.Stream,
    capture_output: bool,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Any],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Iterator[chat_completion_chunk.ChatCompletionChunk]:
    items: List[chat_completion_chunk.ChatCompletionChunk] = []

    try:
        for item in generator:
            items.append(item)

            yield item

    finally:
        output = generations_aggregator(items)
        finally_callback(
            output=output,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=capture_output,
        )


async def wrap_async_stream(
    generator: openai.AsyncStream,
    capture_output: bool,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Any],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> AsyncIterator[chat_completion_chunk.ChatCompletionChunk]:
    items: List[chat_completion_chunk.ChatCompletionChunk] = []

    try:
        async for item in generator:
            items.append(item)

            yield item

    finally:
        output = generations_aggregator(items)
        finally_callback(
            output=output,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=capture_output,
        )

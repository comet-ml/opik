import dataclasses
import random
import uuid
from datetime import datetime, timedelta
from typing import List

from opik.message_processing import messages
from opik.types import ErrorInfoDict


@dataclasses.dataclass
class LongStr:
    value: str

    def __str__(self) -> str:
        return self.value[1] + ".." + self.value[-1]

    def __repr__(self) -> str:
        return str(self)


ONE_MEGABYTE = 1024 * 1024


def fake_create_trace_message_batch(
    count: int = 1000, approximate_trace_size: int = ONE_MEGABYTE
) -> List[messages.CreateTraceMessage]:
    """
    Factory method to create a batch with a specified number of
    CreateTraceMessage objects initialized with fake data.

    Args:
        approximate_trace_size: The approximate size of each trace in megabytes
        count: Number of CreateTraceMessage objects to include in the batch (default: 1000)

    Returns:
        CreateTraceBatchMessage containing the specified number of fake CreateTraceMessage objects
    """
    dummy_traces = []

    for i in range(count):
        # Generate a unique trace ID
        trace_id = str(uuid.uuid4())

        # Create a random start time within the last 24 hours
        start_time = datetime.now() - timedelta(
            hours=random.randint(0, 23),
            minutes=random.randint(0, 59),
            seconds=random.randint(0, 59),
        )

        # Randomly decide if the trace has ended
        has_ended = random.choice([True, False])
        end_time = (
            start_time + timedelta(seconds=random.randint(1, 3600))
            if has_ended
            else None
        )

        # Generate dummy input data
        input_data = {
            "prompt": f"This is a dummy prompt #{i}",
            "parameters": {
                "temperature": round(random.uniform(0.1, 1.0), 2),
                "max_tokens": random.randint(10, 1000),
                "long_string": LongStr("a" * approximate_trace_size),
            },
        }

        # Generate dummy output data if the trace has ended
        output_data = (
            {
                "response": f"This is a dummy response for prompt #{i}",
                "tokens_used": random.randint(10, 500),
            }
            if has_ended
            else None
        )

        # Generate dummy metadata
        metadata = {
            "model": random.choice(["gpt-3.5-turbo", "gpt-4", "claude-2", "llama-2"]),
            "environment": random.choice(["production", "staging", "development"]),
            "client_id": f"client-{random.randint(1000, 9999)}",
        }

        # Generate random tags
        available_tags = [
            "important",
            "experiment",
            "production",
            "test",
            "debug",
            "high-priority",
            "low-priority",
        ]
        tags = random.sample(
            available_tags, k=random.randint(0, min(3, len(available_tags)))
        )

        # Randomly decide if there's an error
        has_error = random.random() < 0.1  # 10% chance of error
        error_info = (
            ErrorInfoDict(
                exception_type=random.choice(
                    ["TimeoutError", "ValidationError", "AuthenticationError"]
                ),
                traceback=f"Dummy stacktrace for error in trace #{i}",
            )
            if has_error
            else None
        )

        # Generate a thread ID for some traces
        thread_id = (
            str(uuid.uuid4()) if random.random() < 0.7 else None
        )  # 70% chance of having a thread ID

        # Create the trace message
        trace_message = messages.CreateTraceMessage(
            trace_id=trace_id,
            project_name="dummy-project",
            name=f"Dummy Trace #{i}",
            start_time=start_time,
            end_time=end_time,
            input=input_data,
            output=output_data,
            metadata=metadata,
            tags=tags,
            error_info=error_info,
            thread_id=thread_id,
        )

        dummy_traces.append(trace_message)

    return dummy_traces


def fake_span_create_message_batch(
    count: int = 1000, approximate_span_size: int = ONE_MEGABYTE
) -> List[messages.CreateSpanMessage]:
    """
    Factory method to create a list with a specified number of
    CreateSpanMessage objects initialized with fake data.

    Args:
        approximate_span_size: The approximate size of each span in megabytes
        count: Number of CreateSpanMessage objects to include in the batch (default: 1000)

    Returns:
        CreateSpansBatchMessage containing the specified number of fake CreateSpanMessage objects
    """
    dummy_spans = []

    for i in range(count):
        # Generate a unique span ID
        span_id = str(uuid.uuid4())

        # Create a random start time within the last 24 hours
        start_time = datetime.now() - timedelta(
            hours=random.randint(0, 23),
            minutes=random.randint(0, 59),
            seconds=random.randint(0, 59),
        )

        # Randomly decide if the span has ended
        has_ended = random.choice([True, False])
        end_time = (
            start_time + timedelta(seconds=random.randint(1, 3600))
            if has_ended
            else None
        )

        # Generate dummy input data
        input_data = {
            "prompt": f"This is a dummy prompt #{i}",
            "parameters": {
                "temperature": round(random.uniform(0.1, 1.0), 2),
                "max_tokens": random.randint(10, 1000),
                "long_string": LongStr("a" * approximate_span_size),
            },
        }

        # Generate dummy output data if the span has ended
        output_data = (
            {
                "response": f"This is a dummy response for prompt #{i}",
                "tokens_used": random.randint(10, 500),
            }
            if has_ended
            else None
        )

        # Generate dummy metadata
        metadata = {
            "model": random.choice(["gpt-3.5-turbo", "gpt-4", "claude-2", "llama-2"]),
            "environment": random.choice(["production", "staging", "development"]),
            "client_id": f"client-{random.randint(1000, 9999)}",
        }

        # Generate random tags
        available_tags = [
            "important",
            "experiment",
            "production",
            "test",
            "debug",
            "high-priority",
            "low-priority",
        ]
        tags = random.sample(
            available_tags, k=random.randint(0, min(3, len(available_tags)))
        )

        # Randomly decide if there's an error
        has_error = random.random() < 0.1  # 10% chance of error
        error_info = (
            ErrorInfoDict(
                exception_type=random.choice(
                    ["TimeoutError", "ValidationError", "AuthenticationError"]
                ),
                traceback=f"Dummy stacktrace for error in trace #{i}",
            )
            if has_error
            else None
        )

        # Create the span message
        span_message = messages.CreateSpanMessage(
            span_id=span_id,
            trace_id=str(uuid.uuid4()),
            parent_span_id=span_id,  # This is wrong, but it's okay for dummy data
            project_name="dummy-project",
            name=f"Dummy Span #{i}",
            start_time=start_time,
            end_time=end_time,
            input=input_data,
            output=output_data,
            metadata=metadata,
            tags=tags,
            error_info=error_info,
            type="general",
            usage=None,
            model=metadata["model"],
            provider=None,
            total_cost=random.random() * 0.01,
        )

        dummy_spans.append(span_message)

    return dummy_spans

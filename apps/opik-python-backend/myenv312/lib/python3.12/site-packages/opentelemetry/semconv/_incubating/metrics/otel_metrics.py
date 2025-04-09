# Copyright The OpenTelemetry Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


from typing import Final

from opentelemetry.metrics import Counter, Meter, UpDownCounter

OTEL_SDK_EXPORTER_SPAN_EXPORTED_COUNT: Final = (
    "otel.sdk.exporter.span.exported.count"
)
"""
The number of spans for which the export has finished, either successful or failed
Instrument: counter
Unit: {span}
Note: For successful exports, `error.type` MUST NOT be set. For failed exports, `error.type` must contain the failure cause.
For exporters with partial success semantics (e.g. OTLP with `rejected_spans`), rejected spans must count as failed and only non-rejected spans count as success.
If no rejection reason is available, `rejected` SHOULD be used as value for `error.type`.
"""


def create_otel_sdk_exporter_span_exported_count(meter: Meter) -> Counter:
    """The number of spans for which the export has finished, either successful or failed"""
    return meter.create_counter(
        name=OTEL_SDK_EXPORTER_SPAN_EXPORTED_COUNT,
        description="The number of spans for which the export has finished, either successful or failed",
        unit="{span}",
    )


OTEL_SDK_EXPORTER_SPAN_INFLIGHT_COUNT: Final = (
    "otel.sdk.exporter.span.inflight.count"
)
"""
The number of spans which were passed to the exporter, but that have not been exported yet (neither successful, nor failed)
Instrument: updowncounter
Unit: {span}
Note: For successful exports, `error.type` MUST NOT be set. For failed exports, `error.type` must contain the failure cause.
"""


def create_otel_sdk_exporter_span_inflight_count(
    meter: Meter,
) -> UpDownCounter:
    """The number of spans which were passed to the exporter, but that have not been exported yet (neither successful, nor failed)"""
    return meter.create_up_down_counter(
        name=OTEL_SDK_EXPORTER_SPAN_INFLIGHT_COUNT,
        description="The number of spans which were passed to the exporter, but that have not been exported yet (neither successful, nor failed)",
        unit="{span}",
    )


OTEL_SDK_PROCESSOR_SPAN_PROCESSED_COUNT: Final = (
    "otel.sdk.processor.span.processed.count"
)
"""
The number of spans for which the processing has finished, either successful or failed
Instrument: counter
Unit: {span}
Note: For successful processing, `error.type` MUST NOT be set. For failed processing, `error.type` must contain the failure cause.
For the SDK Simple and Batching Span Processor a span is considered to be processed already when it has been submitted to the exporter, not when the corresponding export call has finished.
"""


def create_otel_sdk_processor_span_processed_count(meter: Meter) -> Counter:
    """The number of spans for which the processing has finished, either successful or failed"""
    return meter.create_counter(
        name=OTEL_SDK_PROCESSOR_SPAN_PROCESSED_COUNT,
        description="The number of spans for which the processing has finished, either successful or failed",
        unit="{span}",
    )


OTEL_SDK_PROCESSOR_SPAN_QUEUE_CAPACITY: Final = (
    "otel.sdk.processor.span.queue.capacity"
)
"""
The maximum number of spans the queue of a given instance of an SDK span processor can hold
Instrument: updowncounter
Unit: {span}
Note: Only applies to span processors which use a queue, e.g. the SDK Batching Span Processor.
"""


def create_otel_sdk_processor_span_queue_capacity(
    meter: Meter,
) -> UpDownCounter:
    """The maximum number of spans the queue of a given instance of an SDK span processor can hold"""
    return meter.create_up_down_counter(
        name=OTEL_SDK_PROCESSOR_SPAN_QUEUE_CAPACITY,
        description="The maximum number of spans the queue of a given instance of an SDK span processor can hold",
        unit="{span}",
    )


OTEL_SDK_PROCESSOR_SPAN_QUEUE_SIZE: Final = (
    "otel.sdk.processor.span.queue.size"
)
"""
The number of spans in the queue of a given instance of an SDK span processor
Instrument: updowncounter
Unit: {span}
Note: Only applies to span processors which use a queue, e.g. the SDK Batching Span Processor.
"""


def create_otel_sdk_processor_span_queue_size(meter: Meter) -> UpDownCounter:
    """The number of spans in the queue of a given instance of an SDK span processor"""
    return meter.create_up_down_counter(
        name=OTEL_SDK_PROCESSOR_SPAN_QUEUE_SIZE,
        description="The number of spans in the queue of a given instance of an SDK span processor",
        unit="{span}",
    )


OTEL_SDK_SPAN_ENDED_COUNT: Final = "otel.sdk.span.ended.count"
"""
The number of created spans for which the end operation was called
Instrument: counter
Unit: {span}
Note: For spans with `recording=true`: Implementations MUST record both `otel.sdk.span.live.count` and `otel.sdk.span.ended.count`.
For spans with `recording=false`: If implementations decide to record this metric, they MUST also record `otel.sdk.span.live.count`.
"""


def create_otel_sdk_span_ended_count(meter: Meter) -> Counter:
    """The number of created spans for which the end operation was called"""
    return meter.create_counter(
        name=OTEL_SDK_SPAN_ENDED_COUNT,
        description="The number of created spans for which the end operation was called",
        unit="{span}",
    )


OTEL_SDK_SPAN_LIVE_COUNT: Final = "otel.sdk.span.live.count"
"""
The number of created spans for which the end operation has not been called yet
Instrument: updowncounter
Unit: {span}
Note: For spans with `recording=true`: Implementations MUST record both `otel.sdk.span.live.count` and `otel.sdk.span.ended.count`.
For spans with `recording=false`: If implementations decide to record this metric, they MUST also record `otel.sdk.span.ended.count`.
"""


def create_otel_sdk_span_live_count(meter: Meter) -> UpDownCounter:
    """The number of created spans for which the end operation has not been called yet"""
    return meter.create_up_down_counter(
        name=OTEL_SDK_SPAN_LIVE_COUNT,
        description="The number of created spans for which the end operation has not been called yet",
        unit="{span}",
    )

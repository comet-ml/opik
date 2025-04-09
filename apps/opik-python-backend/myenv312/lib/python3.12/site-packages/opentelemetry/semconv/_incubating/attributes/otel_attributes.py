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

from enum import Enum
from typing import Final

from deprecated import deprecated

OTEL_COMPONENT_NAME: Final = "otel.component.name"
"""
A name uniquely identifying the instance of the OpenTelemetry component within its containing SDK instance.
Note: Implementations SHOULD ensure a low cardinality for this attribute, even across application or SDK restarts.
E.g. implementations MUST NOT use UUIDs as values for this attribute.

Implementations MAY achieve these goals by following a `<otel.component.type>/<instance-counter>` pattern, e.g. `batching_span_processor/0`.
Hereby `otel.component.type` refers to the corresponding attribute value of the component.

The value of `instance-counter` MAY be automatically assigned by the component and uniqueness within the enclosing SDK instance MUST be guaranteed.
For example, `<instance-counter>` MAY be implemented by using a monotonically increasing counter (starting with `0`), which is incremented every time an
instance of the given component type is started.

With this implementation, for example the first Batching Span Processor would have `batching_span_processor/0`
as `otel.component.name`, the second one `batching_span_processor/1` and so on.
These values will therefore be reused in the case of an application restart.
"""

OTEL_COMPONENT_TYPE: Final = "otel.component.type"
"""
A name identifying the type of the OpenTelemetry component.
Note: If none of the standardized values apply, implementations SHOULD use the language-defined name of the type.
E.g. for Java the fully qualified classname SHOULD be used in this case.
"""

OTEL_LIBRARY_NAME: Final = "otel.library.name"
"""
Deprecated: Use the `otel.scope.name` attribute.
"""

OTEL_LIBRARY_VERSION: Final = "otel.library.version"
"""
Deprecated: Use the `otel.scope.version` attribute.
"""

OTEL_SCOPE_NAME: Final = "otel.scope.name"
"""
Deprecated in favor of stable :py:const:`opentelemetry.semconv.attributes.otel_attributes.OTEL_SCOPE_NAME`.
"""

OTEL_SCOPE_VERSION: Final = "otel.scope.version"
"""
Deprecated in favor of stable :py:const:`opentelemetry.semconv.attributes.otel_attributes.OTEL_SCOPE_VERSION`.
"""

OTEL_SPAN_SAMPLING_RESULT: Final = "otel.span.sampling_result"
"""
The result value of the sampler for this span.
"""

OTEL_STATUS_CODE: Final = "otel.status_code"
"""
Deprecated in favor of stable :py:const:`opentelemetry.semconv.attributes.otel_attributes.OTEL_STATUS_CODE`.
"""

OTEL_STATUS_DESCRIPTION: Final = "otel.status_description"
"""
Deprecated in favor of stable :py:const:`opentelemetry.semconv.attributes.otel_attributes.OTEL_STATUS_DESCRIPTION`.
"""


class OtelComponentTypeValues(Enum):
    BATCHING_SPAN_PROCESSOR = "batching_span_processor"
    """The builtin SDK Batching Span Processor."""
    SIMPLE_SPAN_PROCESSOR = "simple_span_processor"
    """The builtin SDK Simple Span Processor."""
    OTLP_GRPC_SPAN_EXPORTER = "otlp_grpc_span_exporter"
    """OTLP span exporter over gRPC with protobuf serialization."""
    OTLP_HTTP_SPAN_EXPORTER = "otlp_http_span_exporter"
    """OTLP span exporter over HTTP with protobuf serialization."""
    OTLP_HTTP_JSON_SPAN_EXPORTER = "otlp_http_json_span_exporter"
    """OTLP span exporter over HTTP with JSON serialization."""


class OtelSpanSamplingResultValues(Enum):
    DROP = "DROP"
    """The span is not sampled and not recording."""
    RECORD_ONLY = "RECORD_ONLY"
    """The span is not sampled, but recording."""
    RECORD_AND_SAMPLE = "RECORD_AND_SAMPLE"
    """The span is sampled and recording."""


@deprecated(
    reason="Deprecated in favor of stable :py:const:`opentelemetry.semconv.attributes.otel_attributes.OtelStatusCodeValues`."
)  # type: ignore
class OtelStatusCodeValues(Enum):
    OK = "OK"
    """Deprecated in favor of stable :py:const:`opentelemetry.semconv.attributes.otel_attributes.OtelStatusCodeValues.OK`."""
    ERROR = "ERROR"
    """Deprecated in favor of stable :py:const:`opentelemetry.semconv.attributes.otel_attributes.OtelStatusCodeValues.ERROR`."""

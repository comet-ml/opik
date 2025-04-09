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

from re import UNICODE, compile

_SANITIZE_NAME_RE = compile(r"[^a-zA-Z0-9:]+", UNICODE)
# Same as name, but doesn't allow ":"
_SANITIZE_ATTRIBUTE_KEY_RE = compile(r"[^a-zA-Z0-9]+", UNICODE)

# UCUM style annotations which are text enclosed in curly braces https://ucum.org/ucum#para-6.
# This regex is more permissive than UCUM allows and matches any character within curly braces.
_UNIT_ANNOTATION = compile(r"{.*}")

# Remaps common UCUM and SI units to prometheus conventions. Copied from
# https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.101.0/pkg/translator/prometheus/normalize_name.go#L19
# See specification:
# https://github.com/open-telemetry/opentelemetry-specification/blob/v1.33.0/specification/compatibility/prometheus_and_openmetrics.md#metric-metadata-1
_UNIT_MAPPINGS = {
    # Time
    "d": "days",
    "h": "hours",
    "min": "minutes",
    "s": "seconds",
    "ms": "milliseconds",
    "us": "microseconds",
    "ns": "nanoseconds",
    # Bytes
    "By": "bytes",
    "KiBy": "kibibytes",
    "MiBy": "mebibytes",
    "GiBy": "gibibytes",
    "TiBy": "tibibytes",
    "KBy": "kilobytes",
    "MBy": "megabytes",
    "GBy": "gigabytes",
    "TBy": "terabytes",
    # SI
    "m": "meters",
    "V": "volts",
    "A": "amperes",
    "J": "joules",
    "W": "watts",
    "g": "grams",
    # Misc
    "Cel": "celsius",
    "Hz": "hertz",
    # TODO(https://github.com/open-telemetry/opentelemetry-specification/issues/4058): the
    # specification says to normalize "1" to ratio but that may change. Update this mapping or
    # remove TODO once a decision is made.
    "1": "",
    "%": "percent",
}
# Similar to _UNIT_MAPPINGS, but for "per" unit denominator.
# Example: s => per second (singular)
# Copied from https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/80317ce83ed87a2dff0c316bb939afbfaa823d5e/pkg/translator/prometheus/normalize_name.go#L58
_PER_UNIT_MAPPINGS = {
    "s": "second",
    "m": "minute",
    "h": "hour",
    "d": "day",
    "w": "week",
    "mo": "month",
    "y": "year",
}


def sanitize_full_name(name: str) -> str:
    """sanitize the given metric name according to Prometheus rule, including sanitizing
    leading digits

    https://github.com/open-telemetry/opentelemetry-specification/blob/v1.33.0/specification/compatibility/prometheus_and_openmetrics.md#metric-metadata-1
    """
    # Leading number special case
    if name and name[0].isdigit():
        name = "_" + name[1:]
    return _sanitize_name(name)


def _sanitize_name(name: str) -> str:
    """sanitize the given metric name according to Prometheus rule, but does not handle
    sanitizing a leading digit."""
    return _SANITIZE_NAME_RE.sub("_", name)


def sanitize_attribute(key: str) -> str:
    """sanitize the given metric attribute key according to Prometheus rule.

    https://github.com/open-telemetry/opentelemetry-specification/blob/v1.33.0/specification/compatibility/prometheus_and_openmetrics.md#metric-attributes
    """
    # Leading number special case
    if key and key[0].isdigit():
        key = "_" + key[1:]
    return _SANITIZE_ATTRIBUTE_KEY_RE.sub("_", key)


def map_unit(unit: str) -> str:
    """Maps unit to common prometheus metric names if available and sanitizes any invalid
    characters

    See:
    - https://github.com/open-telemetry/opentelemetry-specification/blob/v1.33.0/specification/compatibility/prometheus_and_openmetrics.md#metric-metadata-1
    - https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.101.0/pkg/translator/prometheus/normalize_name.go#L108
    """
    # remove curly brace unit annotations
    unit = _UNIT_ANNOTATION.sub("", unit)

    if unit in _UNIT_MAPPINGS:
        return _UNIT_MAPPINGS[unit]

    # replace "/" with "per" units like m/s -> meters_per_second
    ratio_unit_subparts = unit.split("/", maxsplit=1)
    if len(ratio_unit_subparts) == 2:
        bottom = _sanitize_name(ratio_unit_subparts[1])
        if bottom:
            top = _sanitize_name(ratio_unit_subparts[0])
            top = _UNIT_MAPPINGS.get(top, top)
            bottom = _PER_UNIT_MAPPINGS.get(bottom, bottom)
            return f"{top}_per_{bottom}" if top else f"per_{bottom}"

    return (
        # since units end up as a metric name suffix, they must be sanitized
        _sanitize_name(unit)
        # strip surrounding "_" chars since it will lead to consecutive underscores in the
        # metric name
        .strip("_")
    )

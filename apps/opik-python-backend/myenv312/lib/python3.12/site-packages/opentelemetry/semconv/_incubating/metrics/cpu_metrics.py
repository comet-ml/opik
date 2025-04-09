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


from typing import (
    Callable,
    Final,
    Generator,
    Iterable,
    Optional,
    Sequence,
    Union,
)

from opentelemetry.metrics import (
    CallbackOptions,
    Counter,
    Meter,
    ObservableGauge,
    Observation,
)

# pylint: disable=invalid-name
CallbackT = Union[
    Callable[[CallbackOptions], Iterable[Observation]],
    Generator[Iterable[Observation], CallbackOptions, None],
]

CPU_FREQUENCY: Final = "cpu.frequency"
"""
Operating frequency of the logical CPU in Hertz
Instrument: gauge
Unit: Hz
"""


def create_cpu_frequency(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """Operating frequency of the logical CPU in Hertz"""
    return meter.create_observable_gauge(
        name=CPU_FREQUENCY,
        callbacks=callbacks,
        description="Operating frequency of the logical CPU in Hertz.",
        unit="Hz",
    )


CPU_TIME: Final = "cpu.time"
"""
Seconds each logical CPU spent on each mode
Instrument: counter
Unit: s
"""


def create_cpu_time(meter: Meter) -> Counter:
    """Seconds each logical CPU spent on each mode"""
    return meter.create_counter(
        name=CPU_TIME,
        description="Seconds each logical CPU spent on each mode",
        unit="s",
    )


CPU_UTILIZATION: Final = "cpu.utilization"
"""
For each logical CPU, the utilization is calculated as the change in cumulative CPU time (cpu.time) over a measurement interval, divided by the elapsed time
Instrument: gauge
Unit: 1
"""


def create_cpu_utilization(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """For each logical CPU, the utilization is calculated as the change in cumulative CPU time (cpu.time) over a measurement interval, divided by the elapsed time"""
    return meter.create_observable_gauge(
        name=CPU_UTILIZATION,
        callbacks=callbacks,
        description="For each logical CPU, the utilization is calculated as the change in cumulative CPU time (cpu.time) over a measurement interval, divided by the elapsed time.",
        unit="1",
    )

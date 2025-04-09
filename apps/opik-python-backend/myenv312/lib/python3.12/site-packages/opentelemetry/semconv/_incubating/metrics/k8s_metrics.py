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
    UpDownCounter,
)

# pylint: disable=invalid-name
CallbackT = Union[
    Callable[[CallbackOptions], Iterable[Observation]],
    Generator[Iterable[Observation], CallbackOptions, None],
]

K8S_CRONJOB_ACTIVE_JOBS: Final = "k8s.cronjob.active_jobs"
"""
The number of actively running jobs for a cronjob
Instrument: updowncounter
Unit: {job}
Note: This metric aligns with the `active` field of the
[K8s CronJobStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#cronjobstatus-v1-batch).

This metric SHOULD, at a minimum, be reported against a
[`k8s.cronjob`](../resource/k8s.md#cronjob) resource.
"""


def create_k8s_cronjob_active_jobs(meter: Meter) -> UpDownCounter:
    """The number of actively running jobs for a cronjob"""
    return meter.create_up_down_counter(
        name=K8S_CRONJOB_ACTIVE_JOBS,
        description="The number of actively running jobs for a cronjob",
        unit="{job}",
    )


K8S_DAEMONSET_CURRENT_SCHEDULED_NODES: Final = (
    "k8s.daemonset.current_scheduled_nodes"
)
"""
Number of nodes that are running at least 1 daemon pod and are supposed to run the daemon pod
Instrument: updowncounter
Unit: {node}
Note: This metric aligns with the `currentNumberScheduled` field of the
[K8s DaemonSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#daemonsetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.daemonset`](../resource/k8s.md#daemonset) resource.
"""


def create_k8s_daemonset_current_scheduled_nodes(
    meter: Meter,
) -> UpDownCounter:
    """Number of nodes that are running at least 1 daemon pod and are supposed to run the daemon pod"""
    return meter.create_up_down_counter(
        name=K8S_DAEMONSET_CURRENT_SCHEDULED_NODES,
        description="Number of nodes that are running at least 1 daemon pod and are supposed to run the daemon pod",
        unit="{node}",
    )


K8S_DAEMONSET_DESIRED_SCHEDULED_NODES: Final = (
    "k8s.daemonset.desired_scheduled_nodes"
)
"""
Number of nodes that should be running the daemon pod (including nodes currently running the daemon pod)
Instrument: updowncounter
Unit: {node}
Note: This metric aligns with the `desiredNumberScheduled` field of the
[K8s DaemonSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#daemonsetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.daemonset`](../resource/k8s.md#daemonset) resource.
"""


def create_k8s_daemonset_desired_scheduled_nodes(
    meter: Meter,
) -> UpDownCounter:
    """Number of nodes that should be running the daemon pod (including nodes currently running the daemon pod)"""
    return meter.create_up_down_counter(
        name=K8S_DAEMONSET_DESIRED_SCHEDULED_NODES,
        description="Number of nodes that should be running the daemon pod (including nodes currently running the daemon pod)",
        unit="{node}",
    )


K8S_DAEMONSET_MISSCHEDULED_NODES: Final = "k8s.daemonset.misscheduled_nodes"
"""
Number of nodes that are running the daemon pod, but are not supposed to run the daemon pod
Instrument: updowncounter
Unit: {node}
Note: This metric aligns with the `numberMisscheduled` field of the
[K8s DaemonSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#daemonsetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.daemonset`](../resource/k8s.md#daemonset) resource.
"""


def create_k8s_daemonset_misscheduled_nodes(meter: Meter) -> UpDownCounter:
    """Number of nodes that are running the daemon pod, but are not supposed to run the daemon pod"""
    return meter.create_up_down_counter(
        name=K8S_DAEMONSET_MISSCHEDULED_NODES,
        description="Number of nodes that are running the daemon pod, but are not supposed to run the daemon pod",
        unit="{node}",
    )


K8S_DAEMONSET_READY_NODES: Final = "k8s.daemonset.ready_nodes"
"""
Number of nodes that should be running the daemon pod and have one or more of the daemon pod running and ready
Instrument: updowncounter
Unit: {node}
Note: This metric aligns with the `numberReady` field of the
[K8s DaemonSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#daemonsetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.daemonset`](../resource/k8s.md#daemonset) resource.
"""


def create_k8s_daemonset_ready_nodes(meter: Meter) -> UpDownCounter:
    """Number of nodes that should be running the daemon pod and have one or more of the daemon pod running and ready"""
    return meter.create_up_down_counter(
        name=K8S_DAEMONSET_READY_NODES,
        description="Number of nodes that should be running the daemon pod and have one or more of the daemon pod running and ready",
        unit="{node}",
    )


K8S_DEPLOYMENT_AVAILABLE_PODS: Final = "k8s.deployment.available_pods"
"""
Total number of available replica pods (ready for at least minReadySeconds) targeted by this deployment
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `availableReplicas` field of the
[K8s DeploymentStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#deploymentstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.deployment`](../resource/k8s.md#deployment) resource.
"""


def create_k8s_deployment_available_pods(meter: Meter) -> UpDownCounter:
    """Total number of available replica pods (ready for at least minReadySeconds) targeted by this deployment"""
    return meter.create_up_down_counter(
        name=K8S_DEPLOYMENT_AVAILABLE_PODS,
        description="Total number of available replica pods (ready for at least minReadySeconds) targeted by this deployment",
        unit="{pod}",
    )


K8S_DEPLOYMENT_DESIRED_PODS: Final = "k8s.deployment.desired_pods"
"""
Number of desired replica pods in this deployment
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `replicas` field of the
[K8s DeploymentSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#deploymentspec-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.deployment`](../resource/k8s.md#deployment) resource.
"""


def create_k8s_deployment_desired_pods(meter: Meter) -> UpDownCounter:
    """Number of desired replica pods in this deployment"""
    return meter.create_up_down_counter(
        name=K8S_DEPLOYMENT_DESIRED_PODS,
        description="Number of desired replica pods in this deployment",
        unit="{pod}",
    )


K8S_HPA_CURRENT_PODS: Final = "k8s.hpa.current_pods"
"""
Current number of replica pods managed by this horizontal pod autoscaler, as last seen by the autoscaler
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `currentReplicas` field of the
[K8s HorizontalPodAutoscalerStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#horizontalpodautoscalerstatus-v2-autoscaling)

This metric SHOULD, at a minimum, be reported against a
[`k8s.hpa`](../resource/k8s.md#horizontalpodautoscaler) resource.
"""


def create_k8s_hpa_current_pods(meter: Meter) -> UpDownCounter:
    """Current number of replica pods managed by this horizontal pod autoscaler, as last seen by the autoscaler"""
    return meter.create_up_down_counter(
        name=K8S_HPA_CURRENT_PODS,
        description="Current number of replica pods managed by this horizontal pod autoscaler, as last seen by the autoscaler",
        unit="{pod}",
    )


K8S_HPA_DESIRED_PODS: Final = "k8s.hpa.desired_pods"
"""
Desired number of replica pods managed by this horizontal pod autoscaler, as last calculated by the autoscaler
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `desiredReplicas` field of the
[K8s HorizontalPodAutoscalerStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#horizontalpodautoscalerstatus-v2-autoscaling)

This metric SHOULD, at a minimum, be reported against a
[`k8s.hpa`](../resource/k8s.md#horizontalpodautoscaler) resource.
"""


def create_k8s_hpa_desired_pods(meter: Meter) -> UpDownCounter:
    """Desired number of replica pods managed by this horizontal pod autoscaler, as last calculated by the autoscaler"""
    return meter.create_up_down_counter(
        name=K8S_HPA_DESIRED_PODS,
        description="Desired number of replica pods managed by this horizontal pod autoscaler, as last calculated by the autoscaler",
        unit="{pod}",
    )


K8S_HPA_MAX_PODS: Final = "k8s.hpa.max_pods"
"""
The upper limit for the number of replica pods to which the autoscaler can scale up
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `maxReplicas` field of the
[K8s HorizontalPodAutoscalerSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#horizontalpodautoscalerspec-v2-autoscaling)

This metric SHOULD, at a minimum, be reported against a
[`k8s.hpa`](../resource/k8s.md#horizontalpodautoscaler) resource.
"""


def create_k8s_hpa_max_pods(meter: Meter) -> UpDownCounter:
    """The upper limit for the number of replica pods to which the autoscaler can scale up"""
    return meter.create_up_down_counter(
        name=K8S_HPA_MAX_PODS,
        description="The upper limit for the number of replica pods to which the autoscaler can scale up",
        unit="{pod}",
    )


K8S_HPA_MIN_PODS: Final = "k8s.hpa.min_pods"
"""
The lower limit for the number of replica pods to which the autoscaler can scale down
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `minReplicas` field of the
[K8s HorizontalPodAutoscalerSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#horizontalpodautoscalerspec-v2-autoscaling)

This metric SHOULD, at a minimum, be reported against a
[`k8s.hpa`](../resource/k8s.md#horizontalpodautoscaler) resource.
"""


def create_k8s_hpa_min_pods(meter: Meter) -> UpDownCounter:
    """The lower limit for the number of replica pods to which the autoscaler can scale down"""
    return meter.create_up_down_counter(
        name=K8S_HPA_MIN_PODS,
        description="The lower limit for the number of replica pods to which the autoscaler can scale down",
        unit="{pod}",
    )


K8S_JOB_ACTIVE_PODS: Final = "k8s.job.active_pods"
"""
The number of pending and actively running pods for a job
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `active` field of the
[K8s JobStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#jobstatus-v1-batch).

This metric SHOULD, at a minimum, be reported against a
[`k8s.job`](../resource/k8s.md#job) resource.
"""


def create_k8s_job_active_pods(meter: Meter) -> UpDownCounter:
    """The number of pending and actively running pods for a job"""
    return meter.create_up_down_counter(
        name=K8S_JOB_ACTIVE_PODS,
        description="The number of pending and actively running pods for a job",
        unit="{pod}",
    )


K8S_JOB_DESIRED_SUCCESSFUL_PODS: Final = "k8s.job.desired_successful_pods"
"""
The desired number of successfully finished pods the job should be run with
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `completions` field of the
[K8s JobSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#jobspec-v1-batch).

This metric SHOULD, at a minimum, be reported against a
[`k8s.job`](../resource/k8s.md#job) resource.
"""


def create_k8s_job_desired_successful_pods(meter: Meter) -> UpDownCounter:
    """The desired number of successfully finished pods the job should be run with"""
    return meter.create_up_down_counter(
        name=K8S_JOB_DESIRED_SUCCESSFUL_PODS,
        description="The desired number of successfully finished pods the job should be run with",
        unit="{pod}",
    )


K8S_JOB_FAILED_PODS: Final = "k8s.job.failed_pods"
"""
The number of pods which reached phase Failed for a job
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `failed` field of the
[K8s JobStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#jobstatus-v1-batch).

This metric SHOULD, at a minimum, be reported against a
[`k8s.job`](../resource/k8s.md#job) resource.
"""


def create_k8s_job_failed_pods(meter: Meter) -> UpDownCounter:
    """The number of pods which reached phase Failed for a job"""
    return meter.create_up_down_counter(
        name=K8S_JOB_FAILED_PODS,
        description="The number of pods which reached phase Failed for a job",
        unit="{pod}",
    )


K8S_JOB_MAX_PARALLEL_PODS: Final = "k8s.job.max_parallel_pods"
"""
The max desired number of pods the job should run at any given time
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `parallelism` field of the
[K8s JobSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#jobspec-v1-batch).

This metric SHOULD, at a minimum, be reported against a
[`k8s.job`](../resource/k8s.md#job) resource.
"""


def create_k8s_job_max_parallel_pods(meter: Meter) -> UpDownCounter:
    """The max desired number of pods the job should run at any given time"""
    return meter.create_up_down_counter(
        name=K8S_JOB_MAX_PARALLEL_PODS,
        description="The max desired number of pods the job should run at any given time",
        unit="{pod}",
    )


K8S_JOB_SUCCESSFUL_PODS: Final = "k8s.job.successful_pods"
"""
The number of pods which reached phase Succeeded for a job
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `succeeded` field of the
[K8s JobStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#jobstatus-v1-batch).

This metric SHOULD, at a minimum, be reported against a
[`k8s.job`](../resource/k8s.md#job) resource.
"""


def create_k8s_job_successful_pods(meter: Meter) -> UpDownCounter:
    """The number of pods which reached phase Succeeded for a job"""
    return meter.create_up_down_counter(
        name=K8S_JOB_SUCCESSFUL_PODS,
        description="The number of pods which reached phase Succeeded for a job",
        unit="{pod}",
    )


K8S_NAMESPACE_PHASE: Final = "k8s.namespace.phase"
"""
Describes number of K8s namespaces that are currently in a given phase
Instrument: updowncounter
Unit: {namespace}
Note: This metric SHOULD, at a minimum, be reported against a
[`k8s.namespace`](../resource/k8s.md#namespace) resource.
"""


def create_k8s_namespace_phase(meter: Meter) -> UpDownCounter:
    """Describes number of K8s namespaces that are currently in a given phase"""
    return meter.create_up_down_counter(
        name=K8S_NAMESPACE_PHASE,
        description="Describes number of K8s namespaces that are currently in a given phase.",
        unit="{namespace}",
    )


K8S_NODE_CPU_TIME: Final = "k8s.node.cpu.time"
"""
Total CPU time consumed
Instrument: counter
Unit: s
Note: Total CPU time consumed by the specific Node on all available CPU cores.
"""


def create_k8s_node_cpu_time(meter: Meter) -> Counter:
    """Total CPU time consumed"""
    return meter.create_counter(
        name=K8S_NODE_CPU_TIME,
        description="Total CPU time consumed",
        unit="s",
    )


K8S_NODE_CPU_USAGE: Final = "k8s.node.cpu.usage"
"""
Node's CPU usage, measured in cpus. Range from 0 to the number of allocatable CPUs
Instrument: gauge
Unit: {cpu}
Note: CPU usage of the specific Node on all available CPU cores, averaged over the sample window.
"""


def create_k8s_node_cpu_usage(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """Node's CPU usage, measured in cpus. Range from 0 to the number of allocatable CPUs"""
    return meter.create_observable_gauge(
        name=K8S_NODE_CPU_USAGE,
        callbacks=callbacks,
        description="Node's CPU usage, measured in cpus. Range from 0 to the number of allocatable CPUs",
        unit="{cpu}",
    )


K8S_NODE_MEMORY_USAGE: Final = "k8s.node.memory.usage"
"""
Memory usage of the Node
Instrument: gauge
Unit: By
Note: Total memory usage of the Node.
"""


def create_k8s_node_memory_usage(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """Memory usage of the Node"""
    return meter.create_observable_gauge(
        name=K8S_NODE_MEMORY_USAGE,
        callbacks=callbacks,
        description="Memory usage of the Node",
        unit="By",
    )


K8S_NODE_NETWORK_ERRORS: Final = "k8s.node.network.errors"
"""
Node network errors
Instrument: counter
Unit: {error}
"""


def create_k8s_node_network_errors(meter: Meter) -> Counter:
    """Node network errors"""
    return meter.create_counter(
        name=K8S_NODE_NETWORK_ERRORS,
        description="Node network errors",
        unit="{error}",
    )


K8S_NODE_NETWORK_IO: Final = "k8s.node.network.io"
"""
Network bytes for the Node
Instrument: counter
Unit: By
"""


def create_k8s_node_network_io(meter: Meter) -> Counter:
    """Network bytes for the Node"""
    return meter.create_counter(
        name=K8S_NODE_NETWORK_IO,
        description="Network bytes for the Node",
        unit="By",
    )


K8S_NODE_UPTIME: Final = "k8s.node.uptime"
"""
The time the Node has been running
Instrument: gauge
Unit: s
Note: Instrumentations SHOULD use a gauge with type `double` and measure uptime in seconds as a floating point number with the highest precision available.
The actual accuracy would depend on the instrumentation and operating system.
"""


def create_k8s_node_uptime(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """The time the Node has been running"""
    return meter.create_observable_gauge(
        name=K8S_NODE_UPTIME,
        callbacks=callbacks,
        description="The time the Node has been running",
        unit="s",
    )


K8S_POD_CPU_TIME: Final = "k8s.pod.cpu.time"
"""
Total CPU time consumed
Instrument: counter
Unit: s
Note: Total CPU time consumed by the specific Pod on all available CPU cores.
"""


def create_k8s_pod_cpu_time(meter: Meter) -> Counter:
    """Total CPU time consumed"""
    return meter.create_counter(
        name=K8S_POD_CPU_TIME,
        description="Total CPU time consumed",
        unit="s",
    )


K8S_POD_CPU_USAGE: Final = "k8s.pod.cpu.usage"
"""
Pod's CPU usage, measured in cpus. Range from 0 to the number of allocatable CPUs
Instrument: gauge
Unit: {cpu}
Note: CPU usage of the specific Pod on all available CPU cores, averaged over the sample window.
"""


def create_k8s_pod_cpu_usage(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """Pod's CPU usage, measured in cpus. Range from 0 to the number of allocatable CPUs"""
    return meter.create_observable_gauge(
        name=K8S_POD_CPU_USAGE,
        callbacks=callbacks,
        description="Pod's CPU usage, measured in cpus. Range from 0 to the number of allocatable CPUs",
        unit="{cpu}",
    )


K8S_POD_MEMORY_USAGE: Final = "k8s.pod.memory.usage"
"""
Memory usage of the Pod
Instrument: gauge
Unit: By
Note: Total memory usage of the Pod.
"""


def create_k8s_pod_memory_usage(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """Memory usage of the Pod"""
    return meter.create_observable_gauge(
        name=K8S_POD_MEMORY_USAGE,
        callbacks=callbacks,
        description="Memory usage of the Pod",
        unit="By",
    )


K8S_POD_NETWORK_ERRORS: Final = "k8s.pod.network.errors"
"""
Pod network errors
Instrument: counter
Unit: {error}
"""


def create_k8s_pod_network_errors(meter: Meter) -> Counter:
    """Pod network errors"""
    return meter.create_counter(
        name=K8S_POD_NETWORK_ERRORS,
        description="Pod network errors",
        unit="{error}",
    )


K8S_POD_NETWORK_IO: Final = "k8s.pod.network.io"
"""
Network bytes for the Pod
Instrument: counter
Unit: By
"""


def create_k8s_pod_network_io(meter: Meter) -> Counter:
    """Network bytes for the Pod"""
    return meter.create_counter(
        name=K8S_POD_NETWORK_IO,
        description="Network bytes for the Pod",
        unit="By",
    )


K8S_POD_UPTIME: Final = "k8s.pod.uptime"
"""
The time the Pod has been running
Instrument: gauge
Unit: s
Note: Instrumentations SHOULD use a gauge with type `double` and measure uptime in seconds as a floating point number with the highest precision available.
The actual accuracy would depend on the instrumentation and operating system.
"""


def create_k8s_pod_uptime(
    meter: Meter, callbacks: Optional[Sequence[CallbackT]]
) -> ObservableGauge:
    """The time the Pod has been running"""
    return meter.create_observable_gauge(
        name=K8S_POD_UPTIME,
        callbacks=callbacks,
        description="The time the Pod has been running",
        unit="s",
    )


K8S_REPLICASET_AVAILABLE_PODS: Final = "k8s.replicaset.available_pods"
"""
Total number of available replica pods (ready for at least minReadySeconds) targeted by this replicaset
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `availableReplicas` field of the
[K8s ReplicaSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#replicasetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.replicaset`](../resource/k8s.md#replicaset) resource.
"""


def create_k8s_replicaset_available_pods(meter: Meter) -> UpDownCounter:
    """Total number of available replica pods (ready for at least minReadySeconds) targeted by this replicaset"""
    return meter.create_up_down_counter(
        name=K8S_REPLICASET_AVAILABLE_PODS,
        description="Total number of available replica pods (ready for at least minReadySeconds) targeted by this replicaset",
        unit="{pod}",
    )


K8S_REPLICASET_DESIRED_PODS: Final = "k8s.replicaset.desired_pods"
"""
Number of desired replica pods in this replicaset
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `replicas` field of the
[K8s ReplicaSetSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#replicasetspec-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.replicaset`](../resource/k8s.md#replicaset) resource.
"""


def create_k8s_replicaset_desired_pods(meter: Meter) -> UpDownCounter:
    """Number of desired replica pods in this replicaset"""
    return meter.create_up_down_counter(
        name=K8S_REPLICASET_DESIRED_PODS,
        description="Number of desired replica pods in this replicaset",
        unit="{pod}",
    )


K8S_REPLICATION_CONTROLLER_AVAILABLE_PODS: Final = (
    "k8s.replication_controller.available_pods"
)
"""
Deprecated: Replaced by `k8s.replicationcontroller.available_pods`.
"""


def create_k8s_replication_controller_available_pods(
    meter: Meter,
) -> UpDownCounter:
    """Deprecated, use `k8s.replicationcontroller.available_pods` instead"""
    return meter.create_up_down_counter(
        name=K8S_REPLICATION_CONTROLLER_AVAILABLE_PODS,
        description="Deprecated, use `k8s.replicationcontroller.available_pods` instead.",
        unit="{pod}",
    )


K8S_REPLICATION_CONTROLLER_DESIRED_PODS: Final = (
    "k8s.replication_controller.desired_pods"
)
"""
Deprecated: Replaced by `k8s.replicationcontroller.desired_pods`.
"""


def create_k8s_replication_controller_desired_pods(
    meter: Meter,
) -> UpDownCounter:
    """Deprecated, use `k8s.replicationcontroller.desired_pods` instead"""
    return meter.create_up_down_counter(
        name=K8S_REPLICATION_CONTROLLER_DESIRED_PODS,
        description="Deprecated, use `k8s.replicationcontroller.desired_pods` instead.",
        unit="{pod}",
    )


K8S_REPLICATIONCONTROLLER_AVAILABLE_PODS: Final = (
    "k8s.replicationcontroller.available_pods"
)
"""
Total number of available replica pods (ready for at least minReadySeconds) targeted by this replication controller
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `availableReplicas` field of the
[K8s ReplicationControllerStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#replicationcontrollerstatus-v1-core)

This metric SHOULD, at a minimum, be reported against a
[`k8s.replicationcontroller`](../resource/k8s.md#replicationcontroller) resource.
"""


def create_k8s_replicationcontroller_available_pods(
    meter: Meter,
) -> UpDownCounter:
    """Total number of available replica pods (ready for at least minReadySeconds) targeted by this replication controller"""
    return meter.create_up_down_counter(
        name=K8S_REPLICATIONCONTROLLER_AVAILABLE_PODS,
        description="Total number of available replica pods (ready for at least minReadySeconds) targeted by this replication controller",
        unit="{pod}",
    )


K8S_REPLICATIONCONTROLLER_DESIRED_PODS: Final = (
    "k8s.replicationcontroller.desired_pods"
)
"""
Number of desired replica pods in this replication controller
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `replicas` field of the
[K8s ReplicationControllerSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#replicationcontrollerspec-v1-core)

This metric SHOULD, at a minimum, be reported against a
[`k8s.replicationcontroller`](../resource/k8s.md#replicationcontroller) resource.
"""


def create_k8s_replicationcontroller_desired_pods(
    meter: Meter,
) -> UpDownCounter:
    """Number of desired replica pods in this replication controller"""
    return meter.create_up_down_counter(
        name=K8S_REPLICATIONCONTROLLER_DESIRED_PODS,
        description="Number of desired replica pods in this replication controller",
        unit="{pod}",
    )


K8S_STATEFULSET_CURRENT_PODS: Final = "k8s.statefulset.current_pods"
"""
The number of replica pods created by the statefulset controller from the statefulset version indicated by currentRevision
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `currentReplicas` field of the
[K8s StatefulSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#statefulsetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.statefulset`](../resource/k8s.md#statefulset) resource.
"""


def create_k8s_statefulset_current_pods(meter: Meter) -> UpDownCounter:
    """The number of replica pods created by the statefulset controller from the statefulset version indicated by currentRevision"""
    return meter.create_up_down_counter(
        name=K8S_STATEFULSET_CURRENT_PODS,
        description="The number of replica pods created by the statefulset controller from the statefulset version indicated by currentRevision",
        unit="{pod}",
    )


K8S_STATEFULSET_DESIRED_PODS: Final = "k8s.statefulset.desired_pods"
"""
Number of desired replica pods in this statefulset
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `replicas` field of the
[K8s StatefulSetSpec](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#statefulsetspec-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.statefulset`](../resource/k8s.md#statefulset) resource.
"""


def create_k8s_statefulset_desired_pods(meter: Meter) -> UpDownCounter:
    """Number of desired replica pods in this statefulset"""
    return meter.create_up_down_counter(
        name=K8S_STATEFULSET_DESIRED_PODS,
        description="Number of desired replica pods in this statefulset",
        unit="{pod}",
    )


K8S_STATEFULSET_READY_PODS: Final = "k8s.statefulset.ready_pods"
"""
The number of replica pods created for this statefulset with a Ready Condition
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `readyReplicas` field of the
[K8s StatefulSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#statefulsetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.statefulset`](../resource/k8s.md#statefulset) resource.
"""


def create_k8s_statefulset_ready_pods(meter: Meter) -> UpDownCounter:
    """The number of replica pods created for this statefulset with a Ready Condition"""
    return meter.create_up_down_counter(
        name=K8S_STATEFULSET_READY_PODS,
        description="The number of replica pods created for this statefulset with a Ready Condition",
        unit="{pod}",
    )


K8S_STATEFULSET_UPDATED_PODS: Final = "k8s.statefulset.updated_pods"
"""
Number of replica pods created by the statefulset controller from the statefulset version indicated by updateRevision
Instrument: updowncounter
Unit: {pod}
Note: This metric aligns with the `updatedReplicas` field of the
[K8s StatefulSetStatus](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#statefulsetstatus-v1-apps).

This metric SHOULD, at a minimum, be reported against a
[`k8s.statefulset`](../resource/k8s.md#statefulset) resource.
"""


def create_k8s_statefulset_updated_pods(meter: Meter) -> UpDownCounter:
    """Number of replica pods created by the statefulset controller from the statefulset version indicated by updateRevision"""
    return meter.create_up_down_counter(
        name=K8S_STATEFULSET_UPDATED_PODS,
        description="Number of replica pods created by the statefulset controller from the statefulset version indicated by updateRevision",
        unit="{pod}",
    )

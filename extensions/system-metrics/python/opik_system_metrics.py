"""Application-only process and filesystem metrics for the Opik system-metrics API."""

from __future__ import annotations

import json
import os
import socket
import threading
import urllib.request
from collections import deque
from datetime import datetime, timezone
from typing import Mapping
import uuid

import psutil


class OpikSystemMetrics:
    def __init__(
        self,
        *,
        base_url: str,
        project_id: str,
        service_name: str,
        service_instance_id: str | None = None,
        agent_id: str = "",
        api_key: str | None = None,
        workspace: str | None = None,
        interval_seconds: float = 15,
        filesystem_paths: Mapping[str, str] | None = None,
        max_buffered_points: int = 5_000,
    ) -> None:
        self.endpoint = (
            f"{base_url.rstrip('/')}/v1/private/projects/{project_id}/system-metrics/batch"
        )
        self.service_name = service_name
        self.agent_id = agent_id
        self.instance_id = (
            service_instance_id
            or os.getenv("OPIK_SERVICE_INSTANCE_ID")
            or socket.gethostname()
        )
        self.interval_seconds = interval_seconds
        self.process = psutil.Process()
        # Prime psutil's non-blocking CPU sampler. Subsequent calls return the
        # process CPU used since the previous collection.
        self.process.cpu_percent(interval=None)
        self.filesystem_paths = {
            alias: os.path.realpath(path)
            for alias, path in (filesystem_paths or {}).items()
        }
        self.headers = {"Content-Type": "application/json"}
        if api_key:
            self.headers["Authorization"] = api_key
        if workspace:
            self.headers["Comet-Workspace"] = workspace
        self._points: deque[dict] = deque(maxlen=max_buffered_points)
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> "OpikSystemMetrics":
        if self._thread is not None:
            return self
        self._thread = threading.Thread(
            target=self._run,
            name="opik-system-metrics",
            daemon=True,
        )
        self._thread.start()
        return self

    def record_request(
        self,
        route: str,
        method: str,
        status_code: int,
        duration_seconds: float,
        *,
        direction: str = "server",
        request_kind: str = "http",
        trace_id: str | None = None,
        thread_id: str | None = None,
        extra_attributes: Mapping[str, str] | None = None,
    ) -> None:
        current_trace_id, current_thread_id = self._current_trace_ids()
        attributes = {
            "http.route": route,
            "http.request.method": method,
            "http.response.status_code": str(status_code),
            "direction": direction,
            "request.kind": request_kind,
        }
        resolved_trace_id = trace_id or current_trace_id
        resolved_thread_id = thread_id or current_thread_id
        if resolved_trace_id:
            attributes["trace.id"] = resolved_trace_id
        if resolved_thread_id:
            attributes["thread.id"] = resolved_thread_id
        if extra_attributes:
            attributes.update(extra_attributes)
        self._append("agent.http.requests", "{request}", 1, attributes)
        self._append("agent.http.request.duration", "s", duration_seconds, attributes)

    def record_mcp_request(
        self,
        route: str,
        method: str,
        status_code: int,
        duration_seconds: float,
        *,
        mcp_method: str = "",
        direction: str = "server",
        trace_id: str | None = None,
        thread_id: str | None = None,
    ) -> None:
        extra_attributes = {"mcp.method": mcp_method} if mcp_method else None
        self.record_request(
            route,
            method,
            status_code,
            duration_seconds,
            direction=direction,
            request_kind="mcp",
            trace_id=trace_id,
            thread_id=thread_id,
            extra_attributes=extra_attributes,
        )

    def flush(self) -> bool:
        self._collect_process()
        with self._lock:
            batch = list(self._points)
            self._points.clear()
        if not batch:
            return True

        request = urllib.request.Request(
            self.endpoint,
            data=json.dumps({"metrics": batch}).encode(),
            headers=self.headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                if response.status >= 300:
                    raise RuntimeError(f"Opik metrics returned HTTP {response.status}")
            return True
        except Exception:
            with self._lock:
                for point in reversed(batch):
                    self._points.appendleft(point)
            return False

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=min(self.interval_seconds + 1, 5))
        self.flush()

    def _run(self) -> None:
        while not self._stop.wait(self.interval_seconds):
            self.flush()

    def _collect_process(self) -> None:
        cpu = self.process.cpu_times()
        self._append("agent.process.cpu.time", "s", cpu.user + cpu.system)
        self._append(
            "agent.process.cpu.utilization",
            "%",
            self.process.cpu_percent(interval=None),
        )
        self._append(
            "agent.process.memory",
            "By",
            self.process.memory_info().rss,
            {"state": "rss"},
        )
        self._append(
            "agent.process.memory.utilization",
            "%",
            self.process.memory_percent(),
        )
        self._append("agent.heartbeat", "1", 1)

        for alias, path in self.filesystem_paths.items():
            try:
                usage = psutil.disk_usage(path)
                stats = os.statvfs(path)
            except OSError:
                continue
            for state, value in (
                ("capacity", usage.total),
                ("used", usage.used),
                ("free", usage.free),
            ):
                self._append(
                    "agent.filesystem.usage",
                    "By",
                    value,
                    {"filesystem.alias": alias, "state": state},
                )
            if stats.f_files > 0:
                for state, value in (
                    ("capacity", stats.f_files),
                    ("used", max(stats.f_files - stats.f_ffree, 0)),
                    ("free", stats.f_favail),
                ):
                    self._append(
                        "agent.filesystem.inodes",
                        "{inode}",
                        value,
                        {"filesystem.alias": alias, "state": state},
                    )

    @staticmethod
    def _current_trace_ids() -> tuple[str | None, str | None]:
        try:
            from opik.opik_context import get_current_trace_data
        except ImportError:
            return None, None

        trace = get_current_trace_data()
        if trace is None:
            return None, None
        return str(trace.id), str(trace.thread_id) if trace.thread_id else None

    def _append(
        self,
        metric_name: str,
        unit: str,
        value: float,
        attributes: Mapping[str, str] | None = None,
    ) -> None:
        point = {
            "sample_id": str(uuid.uuid4()),
            "service_name": self.service_name,
            "service_instance_id": self.instance_id,
            "agent_id": self.agent_id,
            "metric_name": metric_name,
            "unit": unit,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "value": value,
            "attributes": dict(attributes or {}),
        }
        with self._lock:
            self._points.append(point)

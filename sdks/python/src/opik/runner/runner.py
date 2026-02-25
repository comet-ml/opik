"""Redis polling loop for the runner."""

import json
import logging
import os
import signal
import tempfile
import time
from datetime import datetime, timezone
from typing import Any, Dict, Optional

LOGGER = logging.getLogger(__name__)

AGENT_RELOAD_INTERVAL = 5
DEBUG_COMMAND_POLL_INTERVAL = 0.5


class Runner:
    """Polls Redis for jobs and executes agents via subprocess."""

    def __init__(
        self,
        runner_id: str,
        redis_url: str,
    ):
        self._runner_id = runner_id
        self._redis_url = redis_url
        self._agents: Dict[str, Dict[str, str]] = {}
        self._running = True
        self._debug_sessions: Dict[str, "DebugSession"] = {}

        signal.signal(signal.SIGTERM, self._handle_signal)
        signal.signal(signal.SIGINT, self._handle_signal)

    def _handle_signal(self, signum: int, frame: Any) -> None:
        LOGGER.info("Received signal %d, shutting down", signum)
        self._running = False

    def run(self) -> None:
        import redis as redis_lib

        client = redis_lib.Redis.from_url(self._redis_url, decode_responses=True)

        self._reload_agents(client)
        LOGGER.info("Runner %s starting poll loop", self._runner_id)

        pending_key = f"opik:jobs:{self._runner_id}:pending"
        last_reload = time.monotonic()

        while self._running:
            try:
                now = time.monotonic()
                if now - last_reload >= AGENT_RELOAD_INTERVAL:
                    self._reload_agents(client)
                    last_reload = now

                self._poll_debug_commands(client)

                job_id = client.rpop(pending_key)
                if job_id is None:
                    time.sleep(DEBUG_COMMAND_POLL_INTERVAL)
                    continue

                self._process_job(client, job_id)
            except Exception:
                LOGGER.exception("Error in poll loop")
                time.sleep(2)

        self._update_status(client, "disconnected")
        LOGGER.info("Runner %s stopped", self._runner_id)

    def _reload_agents(self, client: Any) -> None:
        """Load agents from ~/.opik/agents.json and register them in Redis."""
        from . import config

        agents = config.load_agents()
        if agents == self._agents:
            return

        self._agents = dict(agents)
        LOGGER.info("Loaded %d agent(s): %s", len(agents), list(agents.keys()))

        agents_key = f"opik:runner:{self._runner_id}:agents"
        client.delete(agents_key)
        for agent_name, info in self._agents.items():
            project = info.get("project", "default")
            agent_blob = json.dumps({
                "project": project,
                "params": info.get("params", []),
            })
            client.hset(agents_key, agent_name, agent_blob)
            client.sadd(f"opik:project:{project}:runners", self._runner_id)

        self._update_status(client, "connected")

    def _update_status(self, client: Any, status: str) -> None:
        runner_key = f"opik:runner:{self._runner_id}"
        client.hset(runner_key, "status", status)

    def _process_job(self, client: Any, job_id: str) -> None:
        job_key = f"opik:job:{job_id}"

        job_data = client.hgetall(job_key)
        if not job_data:
            LOGGER.warning("Job %s not found in Redis", job_id)
            return

        agent_name = job_data.get("agent_name")
        if agent_name not in self._agents:
            LOGGER.error("Unknown agent '%s' for job %s", agent_name, job_id)
            client.hset(job_key, mapping={
                "status": "failed",
                "error": f"Unknown agent: {agent_name}",
                "completed_at": _now_iso(),
            })
            return

        is_debug = job_data.get("debug") == "true"

        client.hset(job_key, mapping={
            "status": "running",
            "started_at": _now_iso(),
        })

        inputs_str = job_data.get("inputs", "{}")
        try:
            inputs = json.loads(inputs_str)
        except json.JSONDecodeError:
            inputs = {}

        agent_info = self._agents[agent_name]

        if is_debug:
            self._process_debug_job(client, job_id, job_key, agent_name, agent_info, inputs)
        else:
            self._process_normal_job(client, job_id, job_key, agent_name, agent_info, inputs)

    def _process_normal_job(
        self, client: Any, job_id: str, job_key: str,
        agent_name: str, agent_info: Dict[str, str], inputs: Dict[str, Any],
    ) -> None:
        from .execute import execute_agent

        result = execute_agent(
            agent_name=agent_name,
            python=agent_info["python"],
            file=agent_info["file"],
            inputs=inputs,
            redis_client=client,
            job_id=job_id,
        )

        completed_mapping: dict[str, str] = {"completed_at": _now_iso()}

        if "result" in result:
            completed_mapping["status"] = "completed"
            completed_mapping["result"] = json.dumps(result["result"])
            client.hset(job_key, mapping=completed_mapping)
            LOGGER.info("Job %s completed", job_id)
        else:
            completed_mapping["status"] = "failed"
            completed_mapping["error"] = result.get("error", "Unknown error")
            if "stdout" in result:
                completed_mapping["stdout"] = result["stdout"]
            client.hset(job_key, mapping=completed_mapping)
            LOGGER.error("Job %s failed: %s", job_id, result.get("error"))

    def _process_debug_job(
        self, client: Any, job_id: str, job_key: str,
        agent_name: str, agent_info: Dict[str, str], inputs: Dict[str, Any],
    ) -> None:
        from .execute import execute_agent
        from .debug_session import DebugSession

        fd, graph_file = tempfile.mkstemp(suffix=".json", prefix="opik_graph_")
        os.close(fd)

        try:
            result = execute_agent(
                agent_name=agent_name,
                python=agent_info["python"],
                file=agent_info["file"],
                inputs=inputs,
                redis_client=client,
                job_id=job_id,
                graph_file=graph_file,
                disable_tracing=False,
            )
        finally:
            graph_data = []
            if os.path.exists(graph_file):
                try:
                    with open(graph_file, "r") as f:
                        graph_data = json.load(f)
                except (json.JSONDecodeError, OSError):
                    LOGGER.warning("Failed to read graph file", exc_info=True)
                os.unlink(graph_file)

        completed_mapping: dict[str, str] = {"completed_at": _now_iso()}
        if "result" in result:
            completed_mapping["status"] = "completed"
            completed_mapping["result"] = json.dumps(result["result"])
        else:
            completed_mapping["status"] = "failed"
            completed_mapping["error"] = result.get("error", "Unknown error")
        client.hset(job_key, mapping=completed_mapping)

        if not graph_data:
            LOGGER.error("Debug job %s: no graph captured", job_id)
            return

        project_name = agent_info.get("project", "default")
        debug_trace_id = self._create_debug_trace(agent_name, project_name, inputs)

        session = DebugSession(
            trace_id=debug_trace_id,
            graph=graph_data,
            agent_info={
                "python": agent_info["python"],
                "file": agent_info["file"],
                "project": project_name,
            },
        )

        self._debug_sessions[session.session_id] = session
        session.to_redis(client)

        client.hset(job_key, "debug_session_id", session.session_id)
        LOGGER.info("Debug session %s created with %d graph nodes", session.session_id, len(graph_data))

    def _create_debug_trace(self, agent_name: str, project_name: str, inputs: Dict[str, Any]) -> str:
        import opik

        opik_client = opik.Opik(project_name=project_name)
        trace = opik_client.trace(
            name=f"debug:{agent_name}",
            input=inputs,
            project_name=project_name,
        )
        opik_client.flush()
        return trace.id

    def _poll_debug_commands(self, client: Any) -> None:
        for session_id in list(self._debug_sessions.keys()):
            session = self._debug_sessions[session_id]
            if session.status != "active":
                continue

            cmd_key = f"opik:debug:{session_id}:commands"
            raw_cmd = client.rpop(cmd_key)
            if raw_cmd is None:
                continue

            try:
                self._handle_debug_command(client, session, raw_cmd)
            except Exception:
                LOGGER.exception("Error handling debug command '%s' for session %s", raw_cmd, session_id)

    def _create_debug_span(self, session: "DebugSession", node: Dict[str, Any]) -> None:
        import opik

        project_name = session.agent_info.get("project", "default")
        opik_client = opik.Opik(project_name=project_name)

        original_parent = node.get("parent_span_id")
        new_parent = session.span_id_map.get(original_parent) if original_parent else None

        span = opik_client.span(
            trace_id=session.trace_id,
            name=node["function_name"],
            type=node.get("type", "general"),
            input=node.get("inputs", {}),
            output={"output": node.get("output")} if node.get("output") is not None else None,
            parent_span_id=new_parent,
            project_name=project_name,
        )
        span.end()
        opik_client.flush()

        session.span_id_map[node["node_id"]] = span.id
        session.last_span_id = span.id

    def _handle_debug_command(self, client: Any, session: "DebugSession", raw_cmd: str) -> None:
        if raw_cmd == "step_forward":
            node = session.step_forward()
            if node is None:
                session.status = "completed"
                session.to_redis(client)
                return

            existing_span = session.span_id_map.get(node["node_id"])
            if existing_span:
                session.last_span_id = existing_span
            else:
                self._create_debug_span(session, node)
            LOGGER.info("Debug step_forward: %s (reused=%s)", node["function_name"], bool(existing_span))
            session.to_redis(client)

        elif raw_cmd == "step_back":
            prev_cursor = session.cursor
            session.step_back()
            if session.cursor > 0:
                prev_node = session.graph[session.cursor - 1]
                session.last_span_id = session.span_id_map.get(prev_node["node_id"], "")
            else:
                session.last_span_id = ""
            LOGGER.info("Debug step_back: cursor=%d (was %d)", session.cursor, prev_cursor)
            session.to_redis(client)

        elif raw_cmd == "run_to_end":
            while session.cursor < len(session.graph):
                node = session.step_forward()
                if node is None:
                    break
                if not session.span_id_map.get(node["node_id"]):
                    self._create_debug_span(session, node)
                else:
                    session.last_span_id = session.span_id_map[node["node_id"]]
            session.status = "completed"
            session.to_redis(client)
            LOGGER.info("Debug run_to_end completed for session %s", session.session_id)

        elif raw_cmd.startswith("execute_node:"):
            from .execute import execute_debug_function

            parts = raw_cmd.split(":", 2)
            if len(parts) < 3:
                LOGGER.warning("Invalid execute_node command: %s", raw_cmd)
                return
            node_id = parts[1]
            try:
                new_inputs = json.loads(parts[2])
            except json.JSONDecodeError:
                LOGGER.warning("Invalid inputs in execute_node: %s", raw_cmd)
                return

            node_index = session.get_node_index(node_id)
            if node_index < 0:
                LOGGER.warning("Node %s not found in session %s", node_id, session.session_id)
                return

            node = session.graph[node_index]
            node["inputs"] = new_inputs

            torn = session.tear_off_from(node_index)
            for t in torn:
                session.span_id_map.pop(t["node_id"], None)
            session.span_id_map.pop(node["node_id"], None)
            session.cursor = node_index

            result = execute_debug_function(
                python=session.agent_info["python"],
                file=session.agent_info["file"],
                function_name=node["function_name"],
                inputs=new_inputs,
                trace_id=session.trace_id,
                parent_span_id=session.span_id_map.get(node.get("parent_span_id")),
                project_name=session.agent_info.get("project", "default"),
            )

            if "result" in result:
                node["output"] = result["result"]
            elif "error" in result:
                node["output"] = {"error": result["error"]}

            session.step_forward()
            self._create_debug_span(session, node)
            LOGGER.info("Debug execute_node: %s, torn_off=%d", node["function_name"], len(torn))
            session.to_redis(client)

        elif raw_cmd == "end":
            session.status = "completed"
            session.to_redis(client)
            del self._debug_sessions[session.session_id]
            LOGGER.info("Debug session %s ended", session.session_id)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

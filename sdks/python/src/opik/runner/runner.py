"""Redis polling loop for the runner."""

import json
import logging
import signal
import time
from datetime import datetime, timezone
from typing import Any, Dict

LOGGER = logging.getLogger(__name__)

AGENT_RELOAD_INTERVAL = 5


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
                # Periodically reload agents from ~/.opik/agents.json
                now = time.monotonic()
                if now - last_reload >= AGENT_RELOAD_INTERVAL:
                    self._reload_agents(client)
                    last_reload = now

                job_id = client.rpop(pending_key)
                if job_id is None:
                    time.sleep(1)
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
        # Clear and re-register
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


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

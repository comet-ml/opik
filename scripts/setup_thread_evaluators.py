"""Create thread-level online evaluators on a project for manual testing.

Creates two automation rules so the agentic-tools thread path and the opt-in
Python spans/traces path can be exercised end-to-end:

    1. Trace-thread LLM-as-judge rule with a {{context}} prompt and one INTEGER
       score. Routes through the read / jq / search / get_trace_spans loop when
       the rendered thread context exceeds the configured token threshold.
    2. Trace-thread Python rule that declares `arguments={"spans": "spans"}`.
       The thread Python scorer pre-fetches every span across the thread's
       traces and unpacks the request as kwargs, so the user's metric signature
       is `def score(self, messages, spans=None, traces=None)`.

The Python metric body just counts spans, so `run_thread_traces.py` can assert
that the recorded score value matches the number of spans it logged.

Usage:
    python scripts/setup_thread_evaluators.py \\
        --project-name thread-scoring-smoke \\
        --model gpt-4o-mini

The script connects via the standard opik config (env vars OPIK_URL_OVERRIDE,
OPIK_API_KEY, OPIK_WORKSPACE — or `~/.opik.config`). Pass --cleanup to delete
rules created by a previous run before re-creating them.
"""

from __future__ import annotations

import argparse
import json
import sys
import textwrap
from typing import Any, Dict, List, Optional

import opik

# Tag stamped on rules this script creates so --cleanup can find and delete
# only its own rules, not arbitrary hand-created ones.
RULE_TAG = "thread-evaluators-smoke"
LLM_RULE_NAME = f"thread-llm-as-judge ({RULE_TAG})"
PY_RULE_NAME = f"thread-python-spans ({RULE_TAG})"

# Python thread metric that counts spans + messages. Used by run_thread_traces.py
# to assert that the recorded score equals the number of logged spans, which
# proves the opt-in spans kwarg actually flowed end-to-end.
PYTHON_METRIC_CODE = textwrap.dedent(
    '''
    from typing import Any, List, Optional, Union
    from opik.evaluation.metrics import base_metric, score_result


    class ThreadSpansProbe(base_metric.BaseMetric):
        def __init__(self, name: str = "thread_spans_probe"):
            super().__init__(name=name, track=False)

        def score(
            self,
            messages: List[Any],
            spans: Optional[List[Any]] = None,
            traces: Optional[List[Any]] = None,
        ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
            n_messages = len(messages)
            n_spans = len(spans or [])
            n_traces = len(traces or [])
            return score_result.ScoreResult(
                value=float(n_spans),
                name=self.name,
                reason=f"thread had {n_messages} messages, {n_spans} spans, {n_traces} traces",
            )
    '''
).strip()


def ensure_project(client: opik.Opik, project_name: str) -> str:
    """Returns the project id, creating the project if it doesn't exist."""
    rest = client.rest_client
    page = rest.projects.find_projects(name=project_name, size=1)
    if page.content:
        return str(page.content[0].id)
    created = rest.projects.create_project(name=project_name)
    # create_project returns 201 with the body; SDK exposes the id on the response object.
    # Re-query rather than trust the return shape across versions.
    page = rest.projects.find_projects(name=project_name, size=1)
    if not page.content:
        raise RuntimeError(f"Project '{project_name}' not visible after create — check workspace + permissions")
    return str(page.content[0].id)


def build_llm_rule_payload(project_id: str, model_name: str, sampling_rate: float) -> Dict[str, Any]:
    """Trace-thread LLM-as-judge rule with one INTEGER score.

    The single {{context}} substitution gets replaced with either the full chat-message
    JSON (inline path) or a capped version + trace-id header (agentic-tools path) — the
    routing decision is size-driven inside the backend.
    """
    return {
        "type": "trace_thread_llm_as_judge",
        "name": LLM_RULE_NAME,
        "projectIds": [project_id],
        "samplingRate": sampling_rate,
        "enabled": True,
        "filters": [],
        "code": {
            "model": {
                "name": model_name,
                "temperature": 0.0,
            },
            "messages": [
                {
                    "role": "SYSTEM",
                    "content": (
                        "You are a strict grader. Score the relevance of the assistant's "
                        "responses to the user's questions on a 1-5 scale (5 = highly relevant). "
                        "When the conversation is too large to read inline, use the read, jq, "
                        "search, and get_trace_spans tools to drill into specific traces. Pick "
                        "trace ids from the trace_ids=[...] header that prefixes the context."
                    ),
                },
                {
                    "role": "USER",
                    "content": (
                        "Conversation context:\n\n{{context}}\n\n"
                        "Return ONLY valid JSON of shape "
                        '{"relevance": {"score": <int 1-5>, "reason": "<short>"}}.'
                    ),
                },
            ],
            "schema": [
                {
                    "name": "relevance",
                    "type": "INTEGER",
                    "description": "How relevant the assistant responses are (1-5).",
                },
            ],
        },
    }


def build_python_rule_payload(project_id: str, sampling_rate: float) -> Dict[str, Any]:
    """Trace-thread Python rule that opts into spans via the new arguments field.

    Sending `arguments={"spans": "spans"}` triggers the kwargs-shaped data payload —
    the backend fetches every span across the thread's traces, sends them alongside
    the messages list, and the sandbox runner unpacks the dict as kwargs.
    """
    return {
        "type": "trace_thread_user_defined_metric_python",
        "name": PY_RULE_NAME,
        "projectIds": [project_id],
        "samplingRate": sampling_rate,
        "enabled": True,
        "filters": [],
        "code": {
            "metric": PYTHON_METRIC_CODE,
            "arguments": {"spans": "spans"},
        },
    }


def list_rules(client: opik.Opik, project_id: str) -> List[Dict[str, Any]]:
    """Hits /v1/private/automations/evaluators because the SDK helper doesn't expose
    the unknown {arguments, ...} fields we care about."""
    response = _raw_request(client, "GET", f"v1/private/automations/evaluators?project_id={project_id}&size=1000")
    return response.get("content", [])


def delete_rules_by_name(client: opik.Opik, project_id: str, names: List[str]) -> List[str]:
    existing = list_rules(client, project_id)
    ids = [r["id"] for r in existing if r.get("name") in names]
    if not ids:
        return []
    _raw_request(client, "POST", "v1/private/automations/evaluators/delete", body={"ids": ids})
    return ids


def create_rule(client: opik.Opik, payload: Dict[str, Any]) -> str:
    """Returns the created rule's id (parsed from the Location header)."""
    response = client.rest_client._client_wrapper.httpx_client.request(
        "v1/private/automations/evaluators",
        method="POST",
        json=payload,
        headers={"content-type": "application/json"},
    )
    if response.status_code >= 300:
        raise RuntimeError(
            f"Rule create failed (HTTP {response.status_code}): {response.text}"
        )
    location = response.headers.get("location") or response.headers.get("Location")
    if location:
        # /v1/private/automations/evaluators/{id}
        return location.rsplit("/", 1)[-1]
    # Fallback: re-list and take the newest match by name.
    existing = list_rules(client, payload["projectIds"][0])
    matches = [r for r in existing if r.get("name") == payload["name"]]
    if not matches:
        raise RuntimeError(f"Rule '{payload['name']}' not visible after create — check the backend log")
    return matches[-1]["id"]


def _raw_request(
    client: opik.Opik,
    method: str,
    path: str,
    *,
    body: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Goes through the SDK's configured httpx client so auth + base URL + workspace
    headers are reused. Returns the parsed JSON body, or an empty dict on 204."""
    response = client.rest_client._client_wrapper.httpx_client.request(
        path,
        method=method,
        json=body,
        headers={"content-type": "application/json"} if body is not None else {},
    )
    if response.status_code >= 300:
        raise RuntimeError(f"{method} {path} failed (HTTP {response.status_code}): {response.text}")
    if response.status_code == 204 or not response.content:
        return {}
    return response.json()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project-name", required=True, help="Project to attach the rules to (created if missing).")
    parser.add_argument(
        "--model",
        default="gpt-4o-mini",
        help="LLM judge model. Must match a provider configured in your Opik backend. Default: gpt-4o-mini.",
    )
    parser.add_argument(
        "--sampling-rate",
        type=float,
        default=1.0,
        help="Sampling rate for both rules. Use 1.0 for smoke tests so every thread gets scored.",
    )
    parser.add_argument("--llm-only", action="store_true", help="Only create the LLM-as-judge rule.")
    parser.add_argument("--python-only", action="store_true", help="Only create the Python rule.")
    parser.add_argument(
        "--cleanup",
        action="store_true",
        help="Delete previously-created rules with matching names before re-creating them. "
        "Use this between runs to avoid score duplication.",
    )
    args = parser.parse_args()

    if args.llm_only and args.python_only:
        parser.error("--llm-only and --python-only are mutually exclusive")

    client = opik.Opik(project_name=args.project_name)

    project_id = ensure_project(client, args.project_name)
    print(f"project '{args.project_name}' → id={project_id}", flush=True)

    target_names: List[str] = []
    if not args.python_only:
        target_names.append(LLM_RULE_NAME)
    if not args.llm_only:
        target_names.append(PY_RULE_NAME)

    if args.cleanup:
        deleted = delete_rules_by_name(client, project_id, target_names)
        if deleted:
            print(f"cleanup: deleted {len(deleted)} existing rule(s): {deleted}", flush=True)
        else:
            print("cleanup: no matching rules to delete", flush=True)

    created: Dict[str, str] = {}

    if not args.python_only:
        llm_payload = build_llm_rule_payload(project_id, args.model, args.sampling_rate)
        llm_id = create_rule(client, llm_payload)
        created[LLM_RULE_NAME] = llm_id
        print(f"created LLM-as-judge rule: {llm_id} (model={args.model})", flush=True)

    if not args.llm_only:
        py_payload = build_python_rule_payload(project_id, args.sampling_rate)
        py_id = create_rule(client, py_payload)
        created[PY_RULE_NAME] = py_id
        print(f"created Python rule: {py_id} (arguments={{'spans': 'spans'}})", flush=True)

    print("\nrule ids:")
    print(json.dumps(created, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())

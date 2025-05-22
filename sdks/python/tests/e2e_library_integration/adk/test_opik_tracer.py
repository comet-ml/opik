import os
import subprocess
import time

import pytest
import requests

from opik import synchronization
from opik.integrations.adk import helpers as adk_helpers
from ... import testlib

ADK_SERVER_PORT = 21345
ADK_USER = "user_113"
ADK_SESSION = "session_113"


def _create_user_session(base_url: str, user_id: str, session_id: str) -> bool:
    try:
        url = f"{base_url}/apps/sample_agent/users/{user_id}/sessions/{session_id}"
        response = requests.post(url)
        if response.status_code == 200:
            print(response.json())
            return True
    except requests.exceptions.ConnectionError:
        return False
    return False


@pytest.fixture()
def start_api_server():
    cwd = os.path.dirname(os.path.abspath(__file__))
    os.environ["OPIK_FILE_LOGGING_LEVEL"] = "DEBUG"
    with subprocess.Popen(
        ["adk", "api_server", "--port", str(ADK_SERVER_PORT)],
        cwd=cwd,
    ) as proc:
        base_url = f"http://localhost:{ADK_SERVER_PORT}"
        time.sleep(1)

        # wait until the server is ready and session created
        if not synchronization.until(
            lambda: _create_user_session(
                base_url=base_url, user_id=ADK_USER, session_id=ADK_SESSION
            ),
            allow_errors=True,
            sleep=5,
            max_try_seconds=60,
        ):
            proc.kill()
            raise Exception("Server did not start in time")

        yield base_url

        if proc.stdout is not None:
            print(proc.stdout.read())
        if proc.stderr is not None:
            print(proc.stderr.read())

        proc.terminate()
        proc.wait()


def test_opik_tracer_with_sample_agent(
    opik_client_unique_project_name, start_api_server
) -> None:
    base_url = start_api_server

    # send the request to the ADK API server
    json_data = {
        "app_name": "sample_agent",
        "user_id": ADK_USER,
        "session_id": ADK_SESSION,
        "new_message": {
            "role": "user",
            "parts": [{"text": "Hey, whats the weather in New York today?"}],
        },
    }
    result = requests.post(
        f"{base_url}/run",
        json=json_data,
    )
    print("Response: ", result.text)
    assert result.status_code == 200

    traces = opik_client_unique_project_name.search_traces(
        filter_string='input contains "Hey, whats the weather in New York today?"'
    )
    assert len(traces) == 1

    trace = traces[0]
    assert trace.span_count == 3  # two LLM calls and one function call
    assert trace.usage is not None
    testlib.assert_dict_has_keys(
        trace.usage,
        [
            "completion_tokens",
            "original_usage.candidates_token_count",
            "original_usage.prompt_token_count",
            "original_usage.total_token_count",
            "prompt_tokens",
            "total_tokens",
        ],
    )

    spans = opik_client_unique_project_name.search_spans()
    assert len(spans) == 3
    assert spans[0].provider == adk_helpers.get_adk_provider()
    assert spans[2].provider == adk_helpers.get_adk_provider()

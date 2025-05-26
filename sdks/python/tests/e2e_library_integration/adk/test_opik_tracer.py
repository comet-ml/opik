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

EXPECTED_USAGE_KEYS_GOOGLE = [
    "completion_tokens",
    "original_usage.candidates_token_count",
    "original_usage.prompt_token_count",
    "original_usage.total_token_count",
    "prompt_tokens",
    "total_tokens",
]

EXPECTED_USAGE_KEYS_OPENAI = [
    "completion_tokens",
    "original_usage.completion_tokens",
    "original_usage.completion_tokens_details.accepted_prediction_tokens",
    "original_usage.completion_tokens_details.audio_tokens",
    "original_usage.completion_tokens_details.reasoning_tokens",
    "original_usage.completion_tokens_details.rejected_prediction_tokens",
    "original_usage.prompt_tokens",
    "original_usage.prompt_tokens_details.audio_tokens",
    "original_usage.prompt_tokens_details.cached_tokens",
    "original_usage.total_tokens",
    "prompt_tokens",
    "total_tokens",
]


def _create_user_session(
    base_url: str,
    user_id: str,
    session_id: str,
    agent_name: str,
) -> bool:
    try:
        url = f"{base_url}/apps/{agent_name}/users/{user_id}/sessions/{session_id}"
        response = requests.post(url)
        if response.status_code == 200:
            print(response.json())
            return True
    except requests.exceptions.ConnectionError:
        return False
    return False


@pytest.fixture()
def start_api_server(request):
    cwd = os.path.dirname(os.path.abspath(__file__))
    os.environ["OPIK_FILE_LOGGING_LEVEL"] = "DEBUG"

    agent_name = getattr(request, "param", None)
    if agent_name is None:
        agent_name = "sample_agent_openai"  # default

    with subprocess.Popen(
        ["adk", "api_server", "--port", str(ADK_SERVER_PORT)],
        cwd=cwd,
    ) as proc:
        base_url = f"http://localhost:{ADK_SERVER_PORT}"
        time.sleep(1)

        # wait until the server is ready and session created
        if not synchronization.until(
            lambda: _create_user_session(
                base_url=base_url,
                user_id=ADK_USER,
                session_id=ADK_SESSION,
                agent_name=agent_name,
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


@pytest.mark.parametrize("start_api_server", ["sample_agent"], indirect=True)
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
    # print("Response: ", result.text)
    assert result.status_code == 200

    traces = opik_client_unique_project_name.search_traces(
        filter_string='input contains "Hey, whats the weather in New York today?"',
    )
    assert len(traces) == 1

    trace = traces[0]
    assert trace.span_count == 3  # two LLM calls and one function call
    assert trace.usage is not None
    testlib.assert_dict_has_keys(trace.usage, EXPECTED_USAGE_KEYS_GOOGLE)

    spans = opik_client_unique_project_name.search_spans()
    assert len(spans) == 3
    assert spans[0].provider == adk_helpers.get_adk_provider()
    assert spans[2].provider == adk_helpers.get_adk_provider()


@pytest.mark.parametrize("start_api_server", ["sample_agent_openai"], indirect=True)
def test_opik_tracer_with_sample_agent__openai(
    opik_client_unique_project_name, start_api_server
) -> None:
    base_url = start_api_server

    # send the request to the ADK API server
    json_data = {
        "app_name": "sample_agent_openai",
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
    # print("Response: ", result.text)
    assert result.status_code == 200

    traces = opik_client_unique_project_name.search_traces(
        filter_string='input contains "Hey, whats the weather in New York today?"',
    )
    assert len(traces) == 1

    trace = traces[0]
    assert trace.span_count == 3  # two LLM calls and one function call
    assert trace.usage is not None
    testlib.assert_dict_has_keys(trace.usage, EXPECTED_USAGE_KEYS_OPENAI)

    spans = opik_client_unique_project_name.search_spans()

    assert len(spans) == 3
    assert spans[0].type == "llm"
    assert spans[0].provider == "openai"
    assert spans[0].model.startswith("gpt-4o")
    testlib.assert_dict_has_keys(spans[0].usage, EXPECTED_USAGE_KEYS_OPENAI)

    assert spans[2].type == "llm"
    assert spans[2].provider == "openai"
    assert spans[2].model.startswith("gpt-4o")
    testlib.assert_dict_has_keys(spans[2].usage, EXPECTED_USAGE_KEYS_OPENAI)

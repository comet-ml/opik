import os
import shutil
import subprocess
import sys
import time

import certifi
import pytest
import requests

from opik import synchronization
from opik.integrations.adk import helpers as adk_helpers
from opik.llm_usage.openai_chat_completions_usage import OpenAICompletionsUsage
from ... import testlib

# needed for OpenAI agents tests
os.environ["SSL_CERT_FILE"] = certifi.where()

ADK_SERVER_PORT = 21345
ADK_USER = "user_113"
ADK_SESSION = "session_113"

EXPECTED_USAGE_KEYS_GOOGLE = [
    "completion_tokens",
    "prompt_tokens",
    "total_tokens",
    "original_usage.candidates_token_count",
    "original_usage.prompt_token_count",
    "original_usage.total_token_count",
]

EXPECTED_USAGE_KEYS_GOOGLE_REASONING = [
    "completion_tokens",
    "prompt_tokens",
    "total_tokens",
    "original_usage.candidates_token_count",
    "original_usage.prompt_token_count",
    "original_usage.total_token_count",
    "original_usage.thoughts_token_count",
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
        # 409 means the session already exists - is OK with us
        if response.status_code == 200 or response.status_code == 409:
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
        agent_name = "sample_agent"  # default

    # Find the adk command in the current environment
    adk_path = shutil.which("adk")
    if adk_path is None:
        # Fallback: construct path from sys.executable
        venv_bin = os.path.dirname(sys.executable)
        adk_path = os.path.join(venv_bin, "adk")

    if not os.path.exists(adk_path):
        raise RuntimeError(f"ADK command not found. Tried: {adk_path}")

    with subprocess.Popen(
        [adk_path, "api_server", "--port", str(ADK_SERVER_PORT)],
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
    assert result.status_code == 200, (
        f"ADK /run returned {result.status_code}. Response: {result.text!r}"
    )

    traces = opik_client_unique_project_name.search_traces(
        filter_string='input contains "Hey, whats the weather in New York today?"',
        wait_for_at_least=1,
        wait_for_timeout=30,
    )
    assert len(traces) == 1

    trace = traces[0]
    assert trace.span_count == 3  # two LLM calls and one function call
    assert trace.usage is not None
    assert "adk_invocation_id" in trace.metadata.keys()
    assert trace.metadata["created_from"] == "google-adk"
    testlib.assert_dict_has_keys(trace.usage, EXPECTED_USAGE_KEYS_GOOGLE)

    spans = opik_client_unique_project_name.search_spans(wait_for_at_least=3)
    assert len(spans) == 3
    assert spans[0].provider == adk_helpers.get_adk_provider()
    assert spans[2].provider == adk_helpers.get_adk_provider()
    testlib.assert_dict_has_keys(spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    testlib.assert_dict_has_keys(spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)


@pytest.mark.parametrize("start_api_server", ["sample_agent_sse"], indirect=True)
def test_opik_tracer_with_sample_agent_sse(
    opik_client_unique_project_name, start_api_server
) -> None:
    """Run the test against the SSE endpoint with streaming enabled using the gemini-2.5-flash model."""
    base_url = start_api_server

    # send the request to the ADK API server
    json_data = {
        "app_name": "sample_agent_sse",
        "user_id": ADK_USER,
        "session_id": ADK_SESSION,
        "new_message": {
            "role": "user",
            "parts": [{"text": "Hey, whats the weather in New York today?"}],
        },
        "streaming": True,
    }

    result = requests.post(
        f"{base_url}/run_sse",
        json=json_data,
    )
    # print("Response: ", result.text)
    assert result.status_code == 200

    traces = opik_client_unique_project_name.search_traces(
        filter_string='input contains "Hey, whats the weather in New York today?"',
        wait_for_at_least=1,
        wait_for_timeout=30,
    )
    assert len(traces) == 1

    trace = traces[0]
    assert trace.span_count == 3  # two LLM calls and one function call
    assert trace.usage is not None
    assert "adk_invocation_id" in trace.metadata.keys()
    assert trace.metadata["created_from"] == "google-adk"
    testlib.assert_dict_keys_in_list(trace.usage, EXPECTED_USAGE_KEYS_GOOGLE_REASONING)

    spans = opik_client_unique_project_name.search_spans()
    assert len(spans) == 3
    assert spans[0].provider == adk_helpers.get_adk_provider()
    assert spans[2].provider == adk_helpers.get_adk_provider()
    testlib.assert_dict_keys_in_list(
        spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE_REASONING
    )
    testlib.assert_dict_keys_in_list(
        spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE_REASONING
    )


@pytest.mark.skip(
    reason="Skipping due to flakiness with OpenAI API calls. Re-enable once https://github.com/google/adk-python/pull/4303 is merged."
)
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
    print("Response: ", result.text)
    assert result.status_code == 200

    traces = opik_client_unique_project_name.search_traces(
        filter_string='input contains "Hey, whats the weather in New York today?"',
        wait_for_at_least=1,
        wait_for_timeout=30,
    )
    assert len(traces) == 1

    trace = traces[0]
    assert trace.span_count >= 3  # two LLM calls and one function call + duplicates
    assert trace.usage is not None
    assert "adk_invocation_id" in trace.metadata.keys()
    assert trace.metadata["created_from"] == "google-adk"
    OpenAICompletionsUsage.from_original_usage_dict(trace.usage)

    spans = opik_client_unique_project_name.search_spans()

    assert len(spans) >= 3  # sometimes it duplicates calls to the function
    for span in spans:
        if span.type == "llm":
            assert span.provider == "openai"
            assert span.model.startswith("gpt-4o")
            OpenAICompletionsUsage.from_original_usage_dict(span.usage)
        elif span.type == "tool":
            assert span.name == "get_weather"


@pytest.mark.parametrize("start_api_server", ["sample_agent_anthropic"], indirect=True)
def test_opik_tracer_with_sample_agent__anthropic(
    opik_client_unique_project_name, start_api_server
) -> None:
    base_url = start_api_server

    # send the request to the ADK API server
    json_data = {
        "app_name": "sample_agent_anthropic",
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
        filter_string='input contains "Hey, whats the weather in New York today?"',
        wait_for_at_least=1,
        wait_for_timeout=30,
    )
    assert len(traces) == 1

    trace = traces[0]
    assert trace.span_count == 3  # two LLM calls and one function call
    assert trace.usage is not None
    assert "adk_invocation_id" in trace.metadata.keys()
    assert trace.metadata["created_from"] == "google-adk"
    OpenAICompletionsUsage.from_original_usage_dict(trace.usage)

    spans = opik_client_unique_project_name.search_spans()

    assert len(spans) == 3
    assert spans[0].type == "llm"
    assert spans[0].provider == "anthropic"
    assert spans[0].model.startswith("claude-sonnet-4")
    OpenAICompletionsUsage.from_original_usage_dict(spans[0].usage)

    assert spans[2].type == "llm"
    assert spans[2].provider == "anthropic"
    assert spans[2].model.startswith("claude-sonnet-4")
    OpenAICompletionsUsage.from_original_usage_dict(spans[2].usage)

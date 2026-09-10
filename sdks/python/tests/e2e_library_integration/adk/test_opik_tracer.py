import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

import certifi
import pytest
import requests

from opik import synchronization
from opik.integrations.adk import helpers as adk_helpers
from opik.llm_usage.openai_chat_completions_usage import OpenAICompletionsUsage
from ... import llm_constants, testlib

# needed for OpenAI agents tests
os.environ["SSL_CERT_FILE"] = certifi.where()

ADK_SERVER_PORT = 21345
ADK_USER = "user_113"
ADK_SESSION = "session_113"

# Keep in sync with sample_agent_litellm_proxy/agent.py, which cannot import from
# here (ADK loads each sample agent as a top-level module).
LITELLM_PROXY_PORT = 21346
LITELLM_PROXY_ALIAS = "internal-proxy-alias"
LITELLM_PROXY_MASTER_KEY = "sk-opik-e2e-proxy"
LITELLM_PROXY_UPSTREAM_MODEL = llm_constants.LITELLM_OPENAI_GPT_4O_MINI
# Deliberately absurd, orders of magnitude above any real price. It makes the
# assertion independent of the token counts a live model happens to return: no price
# table could produce a cost this large, so a span carrying one can only have got it
# from the proxy.
LITELLM_PROXY_INPUT_COST_PER_TOKEN = 0.01
LITELLM_PROXY_OUTPUT_COST_PER_TOKEN = 0.02
# Any real completion has at least a couple of prompt tokens, so the proxy's price
# puts the cost far above this; a price-table estimate for gpt-4o-mini would be ~1e-5.
LITELLM_PROXY_MIN_EXPECTED_COST = 0.01

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
            assert span.model.startswith(llm_constants.OPENAI_GPT_NANO)
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


@pytest.fixture()
def start_litellm_proxy():
    """A real LiteLLM proxy in front of a real provider, for the cost-forwarding test.

    Opik reads the proxy's ``x-litellm-response-cost`` header, and that header only
    exists when a genuine proxy answers - so this contract spans two processes and
    cannot be covered by the offline tests in
    ``tests/library_integration/adk/test_litellm_wrappers.py``, which can only pin
    the SDK's half of it.

    The alias is priced absurdly on purpose (see the constants above): it makes the
    recorded cost provably the proxy's rather than anything Opik could have derived.
    """
    litellm_path = shutil.which("litellm") or os.path.join(
        os.path.dirname(sys.executable), "litellm"
    )
    if not os.path.exists(litellm_path):
        pytest.skip("litellm[proxy] is not installed, so no proxy can be started")

    config = {
        "model_list": [
            {
                "model_name": LITELLM_PROXY_ALIAS,
                "litellm_params": {
                    "model": LITELLM_PROXY_UPSTREAM_MODEL,
                    "api_key": "os.environ/OPENAI_API_KEY",
                    "input_cost_per_token": LITELLM_PROXY_INPUT_COST_PER_TOKEN,
                    "output_cost_per_token": LITELLM_PROXY_OUTPUT_COST_PER_TOKEN,
                },
            }
        ],
        "litellm_settings": {"drop_params": True},
        "general_settings": {"master_key": LITELLM_PROXY_MASTER_KEY},
    }
    if os.environ.get("OPENAI_ORG_ID"):
        # The key may bill to a specific organization; without it the request goes to
        # the key's default org, which surfaces as a confusing 429.
        config["model_list"][0]["litellm_params"]["organization"] = (
            "os.environ/OPENAI_ORG_ID"
        )

    with tempfile.TemporaryDirectory() as tmp_dir:
        config_path = os.path.join(tmp_dir, "litellm_proxy_config.yaml")
        with open(config_path, "w") as config_file:
            json.dump(config, config_file)  # YAML is a superset of JSON

        with subprocess.Popen(
            [litellm_path, "--config", config_path, "--port", str(LITELLM_PROXY_PORT)],
        ) as proc:
            base_url = f"http://localhost:{LITELLM_PROXY_PORT}"

            def _proxy_is_up() -> bool:
                return (
                    requests.get(f"{base_url}/health/liveliness", timeout=5).status_code
                    == 200
                )

            if not synchronization.until(
                _proxy_is_up, allow_errors=True, sleep=2, max_try_seconds=120
            ):
                proc.kill()
                raise Exception("LiteLLM proxy did not start in time")

            yield base_url

            proc.terminate()
            proc.wait()


@pytest.mark.parametrize(
    "start_api_server", ["sample_agent_litellm_proxy"], indirect=True
)
def test_opik_tracer_with_sample_agent__litellm_proxy__proxy_cost_reaches_the_span(
    opik_client_unique_project_name, start_litellm_proxy, start_api_server
) -> None:
    """The LLM span must carry the cost the proxy computed.

    Opik prices spans from a (provider, model) pair and can resolve neither half of a
    proxied call, so before this was forwarded such spans had no cost at all.
    """
    base_url = start_api_server
    question = "Whats the capital of France, in a few words?"

    result = requests.post(
        f"{base_url}/run",
        json={
            "app_name": "sample_agent_litellm_proxy",
            "user_id": ADK_USER,
            "session_id": ADK_SESSION,
            "new_message": {"role": "user", "parts": [{"text": question}]},
        },
    )
    assert result.status_code == 200, (
        f"ADK /run returned {result.status_code}. Response: {result.text!r}"
    )

    spans = opik_client_unique_project_name.search_spans(
        filter_string='type = "llm"', wait_for_at_least=1, wait_for_timeout=30
    )
    assert len(spans) >= 1

    llm_span = spans[0]
    # The pair that makes this span unpriceable by Opik, which is the whole premise.
    assert llm_span.provider == "litellm_proxy"
    assert llm_span.model == LITELLM_PROXY_ALIAS

    assert llm_span.total_estimated_cost is not None, (
        "the proxy's cost never reached the span"
    )
    # Only the proxy's configured price can produce a cost this large; Opik's own
    # table has no entry for the alias, and the underlying model's real price would
    # put this in the 1e-5 range.
    assert llm_span.total_estimated_cost > LITELLM_PROXY_MIN_EXPECTED_COST, (
        f"cost {llm_span.total_estimated_cost} is too small to be the proxy's "
        f"configured price - it looks derived rather than forwarded"
    )

    # And it must match that price applied to this same call's tokens.
    expected = (
        llm_span.usage["prompt_tokens"] * LITELLM_PROXY_INPUT_COST_PER_TOKEN
        + llm_span.usage["completion_tokens"] * LITELLM_PROXY_OUTPUT_COST_PER_TOKEN
    )
    assert llm_span.total_estimated_cost == pytest.approx(expected, rel=1e-6)

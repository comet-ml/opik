import subprocess
from typing import Tuple

import pytest
import requests

from opik import synchronization

ADK_SERVER_PORT = 21345


def _check_server_running(base_url: str, user_id: str, session_id: str) -> bool:
    try:
        url = f"{base_url}/apps/sample_agent/users/{user_id}/session/{session_id}"
        response = requests.get(url)
        if response.status_code == 200:
            return True
    except requests.exceptions.ConnectionError:
        return False
    return False


@pytest.fixture(scope="module")
def api_server() -> Tuple[str]:
    with subprocess.Popen(
        ["adk", "api_server", "--port", str(ADK_SERVER_PORT)]
    ) as proc:
        base_url = f"http://localhost:{ADK_SERVER_PORT}"
        user = "user_113"
        session = "session_113"

        # wait until the server is ready and session created
        synchronization.wait_for_done(
            check_function=lambda: _check_server_running(
                base_url=base_url, user_id=user, session_id=session
            ),
            timeout=20,
            sleep_time=5,
        )

        yield base_url, user, session

        print(proc.stdout.read())


def test_opik_tracer_with_sample_agent(api_server, fake_backend) -> None:
    base_url, user_id, session_id = api_server

    # send the request to the ADK API server
    result = requests.post(
        f"{base_url}/run",
        json={
            "app_name": "my_sample_agent",
            "user_id": user_id,
            "session_id": session_id,
            "new_message": {
                "role": "user",
                "parts": [{"text": "Hey, whats the weather in New York today?"}],
            },
        },
    )

    print(result.json())
    # assert result.status_code == 200

    print(fake_backend.trace_trees)
    assert len(fake_backend.trace_trees) == 1

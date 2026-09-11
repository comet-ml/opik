# test_post_user_signup.py
from unittest.mock import patch

import pytest

ENDPOINT = "/v1/private/post_user_signup"

def test_successful_signup(client):
    payload = {
        "workspace": "demo-workspace",
        "apiKey": "fake-api-key"
    }
    with patch("opik_backend.post_user_signup.create_demo_data") as mock_create:
        response = client.post(ENDPOINT, json=payload)
        assert response.status_code == 200
        assert response.json["message"] == "Demo data created"
        mock_create.assert_called_once()


def test_missing_workspace_name(client):
    payload = {
        "apiKey": "fake-api-key"
    }
    response = client.post(ENDPOINT, json=payload)
    assert response.status_code == 400
    assert "workspace" in response.json["error"]


def test_missing_api_key(client):
    payload = {
        "workspace": "demo-workspace"
    }
    response = client.post(ENDPOINT, json=payload)
    assert response.status_code == 400
    assert "apiKey" in response.json["error"]

# test_post_user_signup.py
from unittest.mock import patch

import pytest

ENDPOINT = "/v1/private/post_user_signup"

def test_successful_signup(client):
    payload = {
        "workspace_name": "demo-workspace",
        "comet_api_key": "fake-api-key"
    }
    with patch("opik_backend.post_user_signup.create_demo_data") as mock_create:
        response = client.post(ENDPOINT, json=payload)
        assert response.status_code == 200
        assert response.json["message"] == "Demo data created"
        mock_create.assert_called_once()


def test_missing_workspace_name(client):
    payload = {
        "comet_api_key": "fake-api-key"
    }
    response = client.post(ENDPOINT, json=payload)
    assert response.status_code == 400
    assert "workspace_name" in response.json["error"]


def test_missing_api_key(client):
    payload = {
        "workspace_name": "demo-workspace"
    }
    response = client.post(ENDPOINT, json=payload)
    assert response.status_code == 400
    assert "comet_api_key" in response.json["error"]

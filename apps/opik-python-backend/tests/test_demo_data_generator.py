import pytest
import uuid6
import json

from unittest.mock import patch

from opik_backend.demo_data_generator import create_demo_data

def test_create_experiment_items_structure(httpserver):
    baseUrl = httpserver.url_for("/")
    httpserver.expect_request("/v1/private/traces/batch", method="POST").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/spans/batch", method="POST").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/traces", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/spans", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/traces/feedback-scores", method="PUT").respond_with_data(status=204)

    httpserver.expect_request("/v1/private/feedback-definitions", method="POST").respond_with_data(status=201)
    
    httpserver.expect_request("/v1/private/prompts", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/datasets", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/datasets/retrieve", method="POST").respond_with_json({
        "id": str(uuid6.uuid7()),
        "name": "Demo dataset",
        "description": "",
        "metadata": {},
        "created_at": "2024-01-01T00:00:00Z",
        "last_updated_at": "2024-01-01T00:00:00Z"
    })
    
    httpserver.expect_request("/v1/private/datasets/items", method="POST").respond_with_data(status=201)
    datasetItems = {
        "content": [
            { "data": {"input": "What is the best LLM evaluation tool?", "output": "Comet" }, "id": str(uuid6.uuid7()), "source": "sdk" },
            { "data": {"input": "What is the easiest way to start with Opik?", "output": "Read the docs" }, "id": str(uuid6.uuid7()), "source": "sdk" },
            { "data": {"input": "Is Opik open source?", "output": "Yes" } , "id": str(uuid6.uuid7()), "source": "sdk" },
        ],
        "page": 1,
        "size": 3,
        "total": 3
    }

    httpserver.expect_request("v1/private/datasets/items/stream", method="POST").respond_with_data(
        status=200,
        headers={"Content-Type": "application/octet-stream"},
        response_data=b"\n".join(json.dumps(item).encode("utf-8") for item in datasetItems["content"])
    )

    httpserver.expect_request("/v1/private/datasets/items/stream", method="POST").respond_with_data(status=200)

    httpserver.expect_request("/v1/private/datasets/items", method="PUT").respond_with_data(status=204)

    httpserver.expect_request("/v1/private/experiments", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/experiments/items", method="POST").respond_with_data(status=204)
    prompt = {
        "id": str(uuid6.uuid7()),
        "prompt_id": str(uuid6.uuid7()),
        "commit": "12345678",
        "template": "",
        "metadata": {},
        "type": "mustache",
        "variables": []
    }

    httpserver.expect_request("/v1/private/prompts/versions/retrieve", method="POST").respond_with_json(prompt)
    httpserver.expect_request("/v1/private/prompts/versions", method="POST").respond_with_json(prompt)

    create_demo_data(baseUrl, "workspace_name", "comet_api_key")

    httpserver.check_assertions()
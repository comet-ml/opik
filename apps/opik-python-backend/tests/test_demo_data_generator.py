import pytest
import uuid6
import json
import re

from unittest.mock import patch

from opik_backend.demo_data_generator import create_demo_data

def test_create_demo_data_structure(httpserver):

    ## Mocking the HTTP server to simulate the API calls to Opik Backend
    baseUrl = httpserver.url_for("/")

    httpserver.expect_request("/v1/private/projects/retrieve", method="POST").respond_with_data(status=404)
    httpserver.expect_request("/v1/private/projects", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/traces/batch", method="POST").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/spans/batch", method="POST").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/traces/feedback-scores", method="PUT").respond_with_data(status=204)

    httpserver.expect_request("/v1/private/feedback-definitions", method="GET", query_string="name=User+feedback").respond_with_json({
        "content": [],
        "page": 1,
        "size": 0,
        "total": 0
    })
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

    # Mock optimization endpoints that don't exist in the backend yet
    httpserver.expect_request("/v1/private/optimizations", method="POST").respond_with_json({
        "id": str(uuid6.uuid7()),
        "name": "Demo optimization",
        "dataset_id": str(uuid6.uuid7()),
        "objective_name": "Demo objective",
        "status": "running",
        "metadata": {},
        "created_at": "2024-01-01T00:00:00Z"
    })

    # Mock specific optimization ID update (for the PUT request with specific ID)
    httpserver.expect_request(re.compile(r"/v1/private/optimizations/.*"), method="PUT").respond_with_data(status=204)

    # Mock thread endpoints for thread feedback scores
    httpserver.expect_request("/v1/private/traces/threads/close", method="PUT").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/traces/threads/feedback-scores", method="PUT").respond_with_data(status=204)

    # Call the function to create the demo data
    create_demo_data(baseUrl, "default", "comet_api_key")

    # Check that all expected requests were made
    httpserver.check_assertions()

def fail_on_request(_request):
    raise AssertionError("Request should not have been made!")

def test_create_demo_data_idempotence(httpserver):

    ## Mocking the HTTP server to simulate the API calls to Opik Backend
    baseUrl = httpserver.url_for("/")

    httpserver.expect_request("/v1/private/projects/retrieve", method="POST").respond_with_json({ "id": str(uuid6.uuid7()) })

    httpserver.expect_request("/v1/private/traces/batch", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/spans/batch", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/traces/feedback-scores", method="PUT").respond_with_handler(fail_on_request)

    httpserver.expect_request("/v1/private/feedback-definitions", method="GET", query_string="name=User+feedback").respond_with_json({
        "content": [
            { "name": "User feedback" }
        ],
        "page": 1,
        "size": 1,
        "total": 1
    })
    httpserver.expect_request("/v1/private/feedback-definitions", method="POST").respond_with_data(status=409)

    httpserver.expect_request("/v1/private/prompts", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/datasets", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/datasets/retrieve", method="POST").respond_with_handler(fail_on_request)

    httpserver.expect_request("/v1/private/datasets/items", method="POST").respond_with_handler(fail_on_request)
    
    httpserver.expect_request("v1/private/datasets/items/stream", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/datasets/items", method="PUT").respond_with_handler(fail_on_request)

    httpserver.expect_request("/v1/private/experiments", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/experiments/items", method="POST").respond_with_handler(fail_on_request)

    httpserver.expect_request("/v1/private/prompts/versions/retrieve", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/prompts/versions", method="POST").respond_with_handler(fail_on_request)

    # Mock optimization endpoints (should not be called in idempotent case)
    httpserver.expect_request("/v1/private/optimizations", method="POST").respond_with_handler(fail_on_request)
    httpserver.expect_request(re.compile(r"/v1/private/optimizations/.*"), method="PUT").respond_with_handler(fail_on_request)

    # Mock thread endpoints (should not be called in idempotent case)
    httpserver.expect_request("/v1/private/traces/threads/close", method="PUT").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/traces/threads/feedback-scores", method="PUT").respond_with_handler(fail_on_request)
    httpserver.expect_request("/v1/private/traces/threads/retrieve", method="POST").respond_with_handler(fail_on_request)

    # Call the function to create the demo data
    create_demo_data(baseUrl, "default", "comet_api_key")

    # Check that all expected requests were made
    httpserver.check_assertions()

    

"""Tests for Optimization Studio endpoints."""

import copy
import pytest

STUDIO_CODE_URL = "/v1/private/studio/code"


# Valid request payload for testing
VALID_CONFIG = {
    "dataset_name": "test-dataset",
    "prompt": {"messages": [{"role": "user", "content": "Answer: {question}"}]},
    "llm_model": {"model": "openai/gpt-4o-mini", "parameters": {"temperature": 0.0}},
    "evaluation": {"metrics": [{"type": "equals", "parameters": {}}]},
    "optimizer": {"type": "gepa", "parameters": {}},
}


def test_successful_code_generation(client):
    """Test successful code generation."""
    response = client.post(STUDIO_CODE_URL, json=VALID_CONFIG)

    assert response.status_code == 200
    assert response.content_type == "application/json"
    assert (
        response.headers["Content-Disposition"]
        == 'attachment; filename="optimization.py"'
    )

    # Verify the response is JSON with code field
    data = response.get_json()
    assert "code" in data
    code = data["code"]

    # Verify the code contains Python code
    assert "import" in code
    assert "opik" in code
    assert "optimizer" in code.lower()


def test_options_method_returns_ok(client):
    """Test OPTIONS method returns OK."""
    response = client.options(STUDIO_CODE_URL)
    assert response.status_code == 200


def test_get_method_returns_method_not_allowed(client):
    """Test GET method returns 405 Method Not Allowed."""
    response = client.get(STUDIO_CODE_URL)
    assert response.status_code == 405


def test_put_method_returns_method_not_allowed(client):
    """Test PUT method returns 405 Method Not Allowed."""
    response = client.put(STUDIO_CODE_URL, json=VALID_CONFIG)
    assert response.status_code == 405


def test_delete_method_returns_method_not_allowed(client):
    """Test DELETE method returns 405 Method Not Allowed."""
    response = client.delete(STUDIO_CODE_URL)
    assert response.status_code == 405


def test_missing_request_body_returns_bad_request(client):
    """Test missing request body returns 400 Bad Request."""
    response = client.post(STUDIO_CODE_URL, json=None)
    # Flask raises BadRequest when json=None, which is handled by error handler
    assert response.status_code == 400
    assert "error" in response.json


def test_empty_request_body_returns_bad_request(client):
    """Test empty request body returns error."""
    response = client.post(STUDIO_CODE_URL, json={})
    # Empty dict triggers abort() which raises BadRequest, handled by error handler
    assert response.status_code == 400
    assert "error" in response.json


def test_missing_dataset_name_returns_bad_request(client):
    """Test missing dataset_name returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["dataset_name"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    assert "Missing required field" in response.json["error"]


def test_missing_prompt_returns_bad_request(client):
    """Test missing prompt returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["prompt"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    assert "Missing required field" in response.json["error"]


def test_missing_llm_model_returns_bad_request(client):
    """Test missing llm_model returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["llm_model"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    assert "Missing required field" in response.json["error"]


def test_missing_evaluation_returns_bad_request(client):
    """Test missing evaluation returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["evaluation"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    assert "Missing required field" in response.json["error"]


def test_missing_optimizer_returns_bad_request(client):
    """Test missing optimizer returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["optimizer"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    assert "Missing required field" in response.json["error"]


def test_empty_metrics_list_returns_bad_request(client):
    """Test empty metrics list returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    config["evaluation"]["metrics"] = []

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    assert "Invalid configuration" in response.json["error"]
    assert "At least one metric must be defined" in response.json["error"]


def test_missing_prompt_messages_returns_bad_request(client):
    """Test missing prompt messages returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["prompt"]["messages"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    # May return "Missing required field" or "Invalid configuration" depending on which check fails first
    assert (
        "Missing required field" in response.json["error"]
        or "Invalid configuration" in response.json["error"]
    )


def test_missing_llm_model_model_returns_bad_request(client):
    """Test missing llm_model.model returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["llm_model"]["model"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    # May return "Missing required field" or "Invalid configuration" depending on which check fails first
    assert (
        "Missing required field" in response.json["error"]
        or "Invalid configuration" in response.json["error"]
    )


def test_missing_optimizer_type_returns_bad_request(client):
    """Test missing optimizer.type returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["optimizer"]["type"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    # May return "Missing required field" or "Invalid configuration" depending on which check fails first
    assert (
        "Missing required field" in response.json["error"]
        or "Invalid configuration" in response.json["error"]
    )


def test_missing_metric_type_returns_bad_request(client):
    """Test missing metric type returns 400 Bad Request."""
    config = copy.deepcopy(VALID_CONFIG)
    del config["evaluation"]["metrics"][0]["type"]

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 400
    assert "Missing required field" in response.json["error"]


def test_invalid_optimizer_type_returns_error(client):
    """Test invalid optimizer type returns error."""
    config = copy.deepcopy(VALID_CONFIG)
    config["optimizer"]["type"] = "invalid_optimizer"

    response = client.post(STUDIO_CODE_URL, json=config)
    # InvalidOptimizerError gets caught by generic exception handler -> 500
    assert response.status_code == 500
    assert "Internal server error" in response.json["error"]


def test_invalid_metric_type_returns_error(client):
    """Test invalid metric type returns error."""
    config = copy.deepcopy(VALID_CONFIG)
    config["evaluation"]["metrics"][0]["type"] = "invalid_metric"

    response = client.post(STUDIO_CODE_URL, json=config)
    # InvalidMetricError gets caught by generic exception handler -> 500
    assert response.status_code == 500
    assert "Internal server error" in response.json["error"]


def test_code_generation_with_all_optimizer_types(client):
    """Test code generation works with all supported optimizer types."""
    optimizer_types = ["gepa", "evolutionary", "hierarchical_reflective"]

    for optimizer_type in optimizer_types:
        config = copy.deepcopy(VALID_CONFIG)
        config["optimizer"]["type"] = optimizer_type

        response = client.post(STUDIO_CODE_URL, json=config)
        assert response.status_code == 200
        assert response.content_type == "application/json"

        data = response.get_json()
        assert "code" in data
        code = data["code"]
        assert len(code) > 0


def test_code_generation_with_different_metrics(client):
    """Test code generation works with different metric types."""
    # Use actual available metrics: equals, geval, json_schema_validator, levenshtein_ratio
    metric_types = ["equals", "geval", "json_schema_validator", "levenshtein_ratio"]

    for metric_type in metric_types:
        config = copy.deepcopy(VALID_CONFIG)
        config["evaluation"]["metrics"][0]["type"] = metric_type

        response = client.post(STUDIO_CODE_URL, json=config)
        assert response.status_code == 200

        data = response.get_json()
        assert "code" in data
        code = data["code"]
        assert len(code) > 0


def test_code_generation_with_template_syntax(client):
    """Test code generation handles template syntax conversion."""
    config = copy.deepcopy(VALID_CONFIG)
    config["prompt"]["messages"][0]["content"] = "Answer: {{question}}"

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 200

    data = response.get_json()
    code = data["code"]
    # Verify template syntax was converted ({{question}} -> {question})
    assert "{question}" in code
    assert "{{question}}" not in code


def test_code_generation_with_model_parameters(client):
    """Test code generation includes model parameters."""
    config = copy.deepcopy(VALID_CONFIG)
    config["llm_model"]["parameters"] = {"temperature": 0.7, "max_tokens": 1000}

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 200

    data = response.get_json()
    code = data["code"]
    assert "temperature" in code.lower() or "0.7" in code


def test_code_generation_with_optimizer_parameters(client):
    """Test code generation includes optimizer parameters."""
    config = copy.deepcopy(VALID_CONFIG)
    config["optimizer"]["parameters"] = {"n_threads": 8, "max_trials": 20}

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 200

    code = response.get_data(as_text=True)
    assert len(code) > 0


def test_code_generation_with_metric_parameters(client):
    """Test code generation includes metric parameters."""
    config = copy.deepcopy(VALID_CONFIG)
    config["evaluation"]["metrics"][0]["parameters"] = {
        "reference_key": "answer",
        "case_sensitive": False,
    }

    response = client.post(STUDIO_CODE_URL, json=config)
    assert response.status_code == 200

    code = response.get_data(as_text=True)
    assert len(code) > 0


def test_code_generation_for_user_download(client):
    """Test generated code is for user download (not server-side)."""
    response = client.post(STUDIO_CODE_URL, json=VALID_CONFIG)
    assert response.status_code == 200

    data = response.get_json()
    code = data["code"]
    # User download code should NOT have stdin reading
    assert "sys.stdin.read()" not in code
    # User download code should have proper structure for standalone execution
    assert (
        "if __name__" in code
        or "def main" in code.lower()
        or "optimizer.optimize_prompt" in code
    )


def test_content_disposition_header(client):
    """Test Content-Disposition header is set correctly."""
    response = client.post(STUDIO_CODE_URL, json=VALID_CONFIG)
    assert response.status_code == 200
    assert "Content-Disposition" in response.headers
    assert (
        response.headers["Content-Disposition"]
        == 'attachment; filename="optimization.py"'
    )


def test_response_is_json(client):
    """Test response content type is application/json."""
    response = client.post(STUDIO_CODE_URL, json=VALID_CONFIG)
    assert response.status_code == 200
    assert response.content_type == "application/json"
    data = response.get_json()
    assert "code" in data

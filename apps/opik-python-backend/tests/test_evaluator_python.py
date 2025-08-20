import pytest
from opik_backend.executor_docker import DockerExecutor
from opik_backend.executor_process import ProcessExecutor
from opik_backend.payload_types import PayloadType

EVALUATORS_URL = "/v1/private/evaluators/python"

def normalize_error_message(error_message: str) -> str:
    """
    Normalize error messages to handle differences between remote and local images.
    Remote images may have double-escaped newlines, this method standardizes them.
    """
    return error_message.replace('\\n', '\n')

@pytest.fixture(params=[DockerExecutor, ProcessExecutor])
def executor(request):
    """Fixture that provides both Docker and Process executors."""
    executor_instance = request.param()
    if hasattr(executor_instance, 'start_services'):
        executor_instance.start_services()

    try:
        yield executor_instance
    finally:
        if hasattr(executor_instance, 'cleanup'):
            executor_instance.cleanup()

@pytest.fixture
def app(executor):
    """Create Flask app with the given executor."""
    from opik_backend import create_app
    app = create_app(should_init_executor=False)
    app.executor = executor  # Override the executor with our parametrized one
    return app

@pytest.fixture
def client(app):
    """Create test client for the app."""
    return app.test_client()

USER_DEFINED_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class UserDefinedEquals(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "user_defined_equals_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        value = 1.0 if output == reference else 0.0
        return score_result.ScoreResult(value=value, name=self.name)
"""

LIST_RESPONSE_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class UserDefinedEquals(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "user_defined_list_equals_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        value = 1.0 if output == reference else 0.0
        return [score_result.ScoreResult(value=value, name=self.name), score_result.ScoreResult(value=0.5, name=self.name)]
"""

INVALID_METRIC = """
from typing import

from opik.evaluation.metrics import base_metric, score_result


class UserDefinedEquals(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "user_defined_equals_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        value = 1.0 if output == reference else 0.0
        return score_result.ScoreResult(value=value, name=self.name)
"""

MISSING_BASE_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class UserDefinedEquals():
    def __init__(
        self,
        name: str = "user_defined_equals_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        value = 1.0 if output == reference else 0.0
        return score_result.ScoreResult(value=value, name=self.name)
"""

CONSTRUCTOR_EXCEPTION_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class UserDefinedEquals(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "user_defined_equals_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )
        raise Exception("Exception in constructor")

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        value = 1.0 if output == reference else 0.0
        return score_result.ScoreResult(value=value, name=self.name)
"""

SCORE_EXCEPTION_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class UserDefinedEquals(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "user_defined_equals_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        raise Exception("Exception while scoring")
"""

MISSING_SCORE_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class UserDefinedEquals(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "user_defined_equals_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        return None
"""

FLASK_INJECTION_METRIC = """
from typing import Any

import flask
from opik.evaluation.metrics import base_metric, score_result


class FlaskInjectionMetric(base_metric.BaseMetric):
    def __init__(self, name: str = "flask_injection_metric", ):
        super().__init__(name=name, track=False)

    def score(self, **ignored_kwargs: Any) -> score_result.ScoreResult:
        # Replace all view functions with a function that returns an error
        def error_response(*args, **kwargs):
            return "Service Unavailable because it was hacked", 503

        for endpoint in flask.current_app.view_functions:
            flask.current_app.view_functions[endpoint] = error_response

        return score_result.ScoreResult(value=0.0, name=self.name)

"""

DATA = {
    "output": "abc",
    "reference": "abc"
}


@pytest.mark.parametrize("data,code, expected", [
    (
            DATA,
            USER_DEFINED_METRIC,
            [
                {
                    "metadata": None,
                    "name": 'user_defined_equals_metric',
                    "reason": None,
                    "scoring_failed": False,
                    "value": 1.0
                }
            ]
    ),
    (
            {"output": "abc", "reference": "ab"},
            USER_DEFINED_METRIC,
            [
                {
                    "metadata": None,
                    "name": 'user_defined_equals_metric',
                    "reason": None,
                    "scoring_failed": False,
                    "value": 0.0
                }
            ]
    ),
    (
            DATA,
            LIST_RESPONSE_METRIC,
            [
                {
                    "metadata": None,
                    "name": 'user_defined_list_equals_metric',
                    "reason": None,
                    "scoring_failed": False,
                    "value": 1.0
                },
                {
                    "metadata": None,
                    "name": 'user_defined_list_equals_metric',
                    "reason": None,
                    "scoring_failed": False,
                    "value": 0.5
                },
            ]
    ),
])
def test_success(client, data, code, expected):
    response = client.post(EVALUATORS_URL, json={
        "data": data,
        "code": code
    })

    assert response.status_code == 200
    assert response.json['scores'] == expected


def test_options_method_returns_ok(client):
    response = client.options(EVALUATORS_URL)
    assert response.status_code == 200
    assert response.get_json() is None


def test_other_method_returns_method_not_allowed(client):
    response = client.get(EVALUATORS_URL)
    assert response.status_code == 405


def test_missing_request_returns_bad_request(client):
    response = client.post(EVALUATORS_URL, json=None)
    assert response.status_code == 400
    assert response.json[
               "error"] == "400 Bad Request: The browser (or proxy) sent a request that this server could not understand."


def test_missing_code_returns_bad_request(client):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA
    })
    assert response.status_code == 400
    assert response.json["error"] == "400 Bad Request: Field 'code' is missing in the request"


def test_missing_data_returns_bad_request(client):
    response = client.post(EVALUATORS_URL, json={
        "code": USER_DEFINED_METRIC
    })
    assert response.status_code == 400
    assert response.json["error"] == "400 Bad Request: Field 'data' is missing in the request"


# Test how the evaluator handles invalid code, including syntax errors and Flask injection attempts
@pytest.mark.parametrize("code, stacktrace", [
    (
            INVALID_METRIC,
            """  File "<string>", line 2
    from typing import
                      ^
SyntaxError: """
    ),
    pytest.param(
            FLASK_INJECTION_METRIC,
            """  File "<string>", line 4, in <module>
ModuleNotFoundError: No module named 'flask'""",
            marks=pytest.mark.skipif(
                lambda: isinstance(app.executor, ProcessExecutor),
                reason="Flask injection test only makes sense for DockerExecutor"
            )
    )
])
def test_invalid_code_returns_bad_request(client, code, stacktrace):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": code
    })
    assert response.status_code == 400
    
    # Check for the main error message (should be consistent)
    error_str = str(response.json["error"])
    assert "400 Bad Request:" in error_str
    assert ("Field 'code' contains invalid Python code" in error_str or 
            "Execution failed: Python code contains an invalid metric" in error_str)
    
    # For syntax error tests, check for key error indicators instead of exact stacktrace
    if "from typing import" in code:
        # Check for syntax error indicators
        assert ("SyntaxError" in error_str or "invalid syntax" in error_str)
    elif "flask" in code.lower():
        # Check for module not found indicators
        assert ("ModuleNotFoundError" in error_str or "No module named 'flask'" in error_str)


def test_missing_metric_returns_bad_request(client):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": MISSING_BASE_METRIC
    })
    assert response.status_code == 400
    assert response.json[
               "error"] == "400 Bad Request: Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'"


@pytest.mark.parametrize("code, stacktrace", [
    (
            CONSTRUCTOR_EXCEPTION_METRIC,
            """  File "<string>", line 16, in __init__
Exception: Exception in constructor"""
    ),
    (
            SCORE_EXCEPTION_METRIC,
            """  File "<string>", line 20, in score
Exception: Exception while scoring"""
    )
])
def test_evaluation_exception_returns_bad_request(client, code, stacktrace):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": code
    })
    assert response.status_code == 400
    
    # Check for the main error message (should be consistent)
    error_str = str(response.json["error"])
    assert "400 Bad Request:" in error_str
    assert "can't be evaluated" in error_str
    
    # Check for the specific exception message instead of exact stacktrace format
    if "Exception in constructor" in stacktrace:
        assert "Exception in constructor" in error_str
    elif "Exception while scoring" in stacktrace:
        assert "Exception while scoring" in error_str


def test_no_scores_returns_bad_request(client):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": MISSING_SCORE_METRIC
    })
    assert response.status_code == 400
    assert response.json[
               "error"] == "400 Bad Request: The provided 'code' field didn't return any 'opik.evaluation.metrics.ScoreResult'"


# ConversationThreadMetric test definitions
CONVERSATION_THREAD_METRIC = """
from typing import Union, List, Any
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.conversation import conversation_thread_metric, types


class TestConversationThreadMetric(conversation_thread_metric.ConversationThreadMetric):
    def __init__(
        self,
        name: str = "test_conversation_thread_metric",
    ):
        super().__init__(
            name=name,
        )

    def score(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        # Simple test metric that counts the number of messages in conversation
        message_count = len(conversation)
        # Score based on whether the conversation has an appropriate length
        value = 1.0 if 2 <= message_count <= 10 else 0.0
        return score_result.ScoreResult(
            value=value, 
            name=self.name,
            reason=f"Conversation has {message_count} messages"
        )
"""



def test_conversation_thread_metric_wrong_data_structure_fails(client, app):
    """Test that ConversationThreadMetric fails when data is a list without type: trace_thread."""
    # This demonstrates the WRONG way - data as a list without type: trace_thread
    wrong_payload = {
        "data": [  # ❌ This is wrong when type is not "trace_thread"
            {
                "role": "user",
                "content": {
                    "query": "My phone won't work",
                    "thread_id": "test-123"
                }
            },
            {
                "role": "assistant",
                "content": {
                    "output": "Let me help you with that."
                }
            }
        ],
        # ❌ Missing "type": "trace_thread" - so backend tries **data unpacking
        "code": CONVERSATION_THREAD_METRIC
    }

    response = client.post(EVALUATORS_URL, json=wrong_payload)

    # Should fail with 400 error about evaluation failure (different images may have different detailed error messages)
    assert response.status_code == 400
    error_str = str(response.json["error"])
    assert "400 Bad Request:" in error_str
    # Check for evaluation failure - the exact error might differ between production and optimized images
    assert ("can't be evaluated" in error_str or 
            "argument after ** must be a mapping, not list" in error_str or
            "evaluation failed" in error_str.lower())


def test_conversation_thread_metric_with_trace_thread_type(client, app):
    """Test that ConversationThreadMetric works with trace_thread type and direct data array."""
    # Test the NEW way - using type: trace_thread with data as direct array
    trace_thread_payload = {
        "data": [  # ✅ Data as direct array works with type: trace_thread
            {
                "role": "user",
                "content": {
                    "query": "My phone won't work",
                    "thread_id": "test-123"
                }
            },
            {
                "role": "assistant",
                "content": {
                    "output": "Let me help you with that."
                }
            }
        ],
        "type": PayloadType.TRACE_THREAD.value,  # ✅ This tells backend to pass data as first positional arg
        "code": CONVERSATION_THREAD_METRIC
    }

    response = client.post(EVALUATORS_URL, json=trace_thread_payload)

    # Should work correctly now
    assert response.status_code == 200
    scores = response.json['scores']
    assert len(scores) == 1
    
    score = scores[0]
    assert score['name'] == 'test_conversation_thread_metric'
    assert score['value'] == 1.0  # 2 messages is within 2-10 range
    assert score['reason'] == "Conversation has 2 messages"
    assert score['scoring_failed'] is False

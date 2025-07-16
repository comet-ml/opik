import pytest
from opik_backend.executor_docker import DockerExecutor
from opik_backend.executor_process import ProcessExecutor

EVALUATORS_URL = "/v1/private/evaluators/python"

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
    assert "400 Bad Request: Field 'code' contains invalid Python code" in str(response.json["error"])
    assert stacktrace in str(response.json["error"])


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
    assert "400 Bad Request: The provided 'code' and 'data' fields can't be evaluated" in str(response.json["error"])
    assert stacktrace in str(response.json["error"])


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
from typing import Any, Union, List

from opik.evaluation.metrics.conversation import conversation_thread_metric, types
from opik.evaluation.metrics import score_result


class TestConversationThreadMetric(conversation_thread_metric.ConversationThreadMetric):
    def __init__(
        self,
        name: str = "test_conversation_thread_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> score_result.ScoreResult:
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

CONVERSATION_THREAD_METRIC_LIST_RESPONSE = """
from typing import Any, Union, List

from opik.evaluation.metrics.conversation import conversation_thread_metric, types
from opik.evaluation.metrics import score_result


class TestConversationThreadMetricList(conversation_thread_metric.ConversationThreadMetric):
    def __init__(
        self,
        name: str = "test_conversation_thread_metric_list",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, conversation: types.Conversation, **kwargs: Any
    ) -> List[score_result.ScoreResult]:
        # Return multiple scores for testing list response
        message_count = len(conversation)
        user_messages = len([msg for msg in conversation if msg.get("role") == "user"])
        assistant_messages = len([msg for msg in conversation if msg.get("role") == "assistant"])
        
        return [
            score_result.ScoreResult(
                value=float(user_messages) / message_count if message_count > 0 else 0.0, 
                name=f"{self.name}_user_ratio"
            ),
            score_result.ScoreResult(
                value=float(assistant_messages) / message_count if message_count > 0 else 0.0, 
                name=f"{self.name}_assistant_ratio"
            )
        ]
"""

# Test data for conversation metrics
CONVERSATION_DATA = {
    "conversation": [
        {
            "role": "user",
            "content": "Hello, how can you help me today?"
        },
        {
            "role": "assistant", 
            "content": "Hello! I'm here to help you with any questions or tasks you might have. What can I assist you with?"
        },
        {
            "role": "user",
            "content": "Can you help me write a Python function?"
        },
        {
            "role": "assistant",
            "content": "Of course! I'd be happy to help you write a Python function. What specific function would you like to create?"
        }
    ]
}

SHORT_CONVERSATION_DATA = {
    "conversation": [
        {
            "role": "user",
            "content": "Hi"
        }
    ]
}

JSON_CONVERSATION_DATA = {
    "conversation": [
        {
            "role": "user",
            "content": {
                "query": "What is the weather like?",
                "location": "New York",
                "timestamp": "2024-01-15T10:30:00Z"
            }
        },
        {
            "role": "assistant", 
            "content": {
                "response": "The weather in New York is currently 72°F with partly cloudy skies.",
                "temperature": 72,
                "condition": "partly_cloudy",
                "humidity": 65
            }
        },
        {
            "role": "user",
            "content": {
                "query": "Will it rain today?",
                "follow_up": True
            }
        },
        {
            "role": "assistant",
            "content": {
                "response": "There's a 30% chance of light rain this afternoon.",
                "precipitation_probability": 0.3,
                "precipitation_type": "light_rain"
            }
        }
    ]
}


def test_conversation_thread_metric_success(client):
    """Test that ConversationThreadMetric works with proper conversation data."""
    response = client.post(EVALUATORS_URL, json={
        "data": CONVERSATION_DATA,
        "code": CONVERSATION_THREAD_METRIC
    })

    assert response.status_code == 200
    scores = response.json['scores']
    assert len(scores) == 1
    
    score = scores[0]
    assert score['name'] == 'test_conversation_thread_metric'
    assert score['value'] == 1.0  # 4 messages is within 2-10 range
    assert score['reason'] == "Conversation has 4 messages"
    assert score['scoring_failed'] is False


def test_conversation_thread_metric_short_conversation(client):
    """Test ConversationThreadMetric with a short conversation (edge case)."""
    response = client.post(EVALUATORS_URL, json={
        "data": SHORT_CONVERSATION_DATA,
        "code": CONVERSATION_THREAD_METRIC
    })

    assert response.status_code == 200
    scores = response.json['scores']
    assert len(scores) == 1
    
    score = scores[0]
    assert score['name'] == 'test_conversation_thread_metric'
    assert score['value'] == 0.0  # 1 message is below the 2-10 range
    assert score['reason'] == "Conversation has 1 messages"
    assert score['scoring_failed'] is False


def test_conversation_thread_metric_list_response(client):
    """Test ConversationThreadMetric that returns a list of scores."""
    response = client.post(EVALUATORS_URL, json={
        "data": CONVERSATION_DATA,
        "code": CONVERSATION_THREAD_METRIC_LIST_RESPONSE
    })

    assert response.status_code == 200
    scores = response.json['scores']
    assert len(scores) == 2
    
    # Check user ratio score
    user_score = scores[0]
    assert user_score['name'] == 'test_conversation_thread_metric_list_user_ratio'
    assert user_score['value'] == 0.5  # 2 user messages out of 4 total
    assert user_score['scoring_failed'] is False
    
    # Check assistant ratio score  
    assistant_score = scores[1]
    assert assistant_score['name'] == 'test_conversation_thread_metric_list_assistant_ratio'
    assert assistant_score['value'] == 0.5  # 2 assistant messages out of 4 total
    assert assistant_score['scoring_failed'] is False


def test_conversation_thread_metric_with_json_content(client):
    """Test ConversationThreadMetric with JSON content instead of string content."""
    response = client.post(EVALUATORS_URL, json={
        "data": JSON_CONVERSATION_DATA,
        "code": CONVERSATION_THREAD_METRIC
    })

    assert response.status_code == 200
    scores = response.json['scores']
    assert len(scores) == 1
    
    score = scores[0]
    assert score['name'] == 'test_conversation_thread_metric'
    assert score['value'] == 1.0  # 4 messages is within 2-10 range
    assert score['reason'] == "Conversation has 4 messages"
    assert score['scoring_failed'] is False

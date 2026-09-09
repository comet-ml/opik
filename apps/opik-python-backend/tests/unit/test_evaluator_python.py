import pytest
from opik_backend.executor_docker import DockerExecutor
from opik_backend.executor_process import ProcessExecutor
from opik_backend.payload_types import PayloadType

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
    scores = response.json['scores']
    assert all(s.get('category_name') is None for s in scores)
    assert [{k: v for k, v in s.items() if k != 'category_name'} for s in scores] == expected


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
@pytest.mark.parametrize("code, stacktraces", [
    (
            INVALID_METRIC,
            [
                """SyntaxError: invalid syntax""",  # DockerExecutor format
                """SyntaxError: Expected one or more names after 'import'"""  # ProcessExecutor format
            ]
    ),
    pytest.param(
            FLASK_INJECTION_METRIC,
            ["""ModuleNotFoundError: No module named 'flask'"""],
            marks=pytest.mark.skipif(
                lambda: isinstance(app.executor, ProcessExecutor),
                reason="Flask injection test only makes sense for DockerExecutor"
            )
    )
])
def test_invalid_code_returns_bad_request(client, code, stacktraces):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": code
    })
    assert response.status_code == 400
    assert "400 Bad Request: Field 'code' contains invalid Python code" in str(response.json["error"])

    # Check that the expected error message is in the response
    error_message = str(response.json["error"])
    # Check if any of the expected stacktraces match
    assert any(stacktrace in error_message for stacktrace in stacktraces), f"None of the expected stacktraces found in error message: {error_message}"


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
            """Exception: Exception in constructor"""
    ),
    (
            SCORE_EXCEPTION_METRIC,
            """Exception: Exception while scoring"""
    )
])
def test_evaluation_exception_returns_bad_request(client, code, stacktrace):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": code
    })
    assert response.status_code == 400
    assert "400 Bad Request: The provided 'code' and 'data' fields can't be evaluated" in str(response.json["error"])

    # Check that the expected error message is in the response
    error_message = str(response.json["error"])
    assert stacktrace in error_message


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

    # Should fail with 400 error about evaluation failure
    assert response.status_code == 400
    assert "400 Bad Request: The provided 'code' and 'data' fields can't be evaluated" in str(response.json["error"])


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


@pytest.fixture
def process_client():
    """Endpoint client pinned to the in-repo ProcessExecutor.

    The Docker executor runs the *published* sandbox image, so it cannot exercise
    an unreleased change to that image's scoring_runner. Pinning keeps these
    assertions about this repo's own code; the sandbox runner's equivalent cases
    are gated by its selftest.sh at image build time.
    """
    executor = ProcessExecutor()
    if hasattr(executor, 'start_services'):
        executor.start_services()
    try:
        from opik_backend import create_app
        app = create_app(should_init_executor=False)
        app.executor = executor
        yield app.test_client()
    finally:
        if hasattr(executor, 'cleanup'):
            executor.cleanup()


REQUIRED_METADATA_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class RequiresMetadata(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "requires_metadata_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, metadata, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            value=1.0, name=self.name, reason=f"metadata={metadata!r}"
        )
"""

OPTIONAL_THRESHOLD_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class OptionalThreshold(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "optional_threshold_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(
        self, output: str, threshold: float = 0.5, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            value=threshold, name=self.name, reason=f"threshold={threshold!r}"
        )
"""

KEYWORD_ONLY_METADATA_METRIC = """
from typing import Any

from opik.evaluation.metrics import base_metric, score_result


class KeywordOnlyMetadata(base_metric.BaseMetric):
    def __init__(
        self,
        name: str = "keyword_only_metadata_metric",
    ):
        super().__init__(
            name=name,
            track=False,
        )

    def score(self, output: str, *, metadata) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            value=1.0, name=self.name, reason=f"metadata={metadata!r}"
        )
"""


# A rule maps each score() parameter to a trace/span field, but a field the entity
# never logged resolves to nothing and reaches the evaluator with that key absent.
# Spreading that as score(**data) used to miss an argument the signature required,
# failing the whole rule -- which is what the shipped default template did on any
# trace logged without metadata.
@pytest.mark.parametrize("code, expected_name, expected_value, expected_reason", [
    (REQUIRED_METADATA_METRIC, "requires_metadata_metric", 1.0, "metadata=None"),
    (KEYWORD_ONLY_METADATA_METRIC, "keyword_only_metadata_metric", 1.0, "metadata=None"),
])
def test_missing_required_argument_is_bound_to_none(
        process_client, code, expected_name, expected_value, expected_reason):
    response = process_client.post(EVALUATORS_URL, json={
        "data": {"output": "abc"},
        "code": code
    })

    assert response.status_code == 200
    scores = response.json["scores"]
    assert len(scores) == 1
    assert scores[0]["name"] == expected_name
    assert scores[0]["value"] == expected_value
    assert scores[0]["reason"] == expected_reason
    assert scores[0]["scoring_failed"] is False


# The counterpart the fill-in must not break: None is a value, so binding it over a
# parameter that has a default would silently replace the default rather than let it
# apply.
def test_missing_optional_argument_keeps_its_default(process_client):
    response = process_client.post(EVALUATORS_URL, json={
        "data": {"output": "abc"},
        "code": OPTIONAL_THRESHOLD_METRIC
    })

    assert response.status_code == 200
    scores = response.json["scores"]
    assert len(scores) == 1
    assert scores[0]["name"] == "optional_threshold_metric"
    assert scores[0]["value"] == 0.5, "the metric's own default must survive"
    assert scores[0]["reason"] == "threshold=0.5"
    assert scores[0]["scoring_failed"] is False


# A resolvable mapping must reach the metric untouched -- the contrast that shows the
# fill-in only covers absence.
def test_present_argument_is_passed_through(process_client):
    response = process_client.post(EVALUATORS_URL, json={
        "data": {"output": "abc", "metadata": '{"env":"test"}'},
        "code": REQUIRED_METADATA_METRIC
    })

    assert response.status_code == 200
    scores = response.json["scores"]
    assert len(scores) == 1
    assert scores[0]["name"] == "requires_metadata_metric"
    assert scores[0]["value"] == 1.0
    assert scores[0]["reason"] == "metadata='{\"env\":\"test\"}'"
    assert scores[0]["scoring_failed"] is False


# Binding absent arguments must not paper over a genuinely wrong call: an argument the
# signature has no place for is still a reported failure, and the reported cause must
# name it rather than coming back empty.
def test_unexpected_argument_still_fails_and_names_the_cause(process_client):
    response = process_client.post(EVALUATORS_URL, json={
        "data": {"output": "abc", "metadata": "x"},
        "code": """
from opik.evaluation.metrics import base_metric, score_result


class NoKwargs(base_metric.BaseMetric):
    def __init__(self, name: str = "no_kwargs_metric"):
        super().__init__(name=name, track=False)

    def score(self, output: str) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)
"""
    })

    assert response.status_code == 400
    error = str(response.json["error"])
    assert "The provided 'code' and 'data' fields can't be evaluated" in error
    assert "unexpected keyword argument 'metadata'" in error, (
        "the cause must be named -- a fixed-length traceback slice used to drop it"
    )

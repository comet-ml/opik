import pytest

EVALUATORS_URL = "/v1/private/evaluators/python"

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


@pytest.mark.parametrize("code, stacktrace", [
    (
            INVALID_METRIC,
            """  File "<string>", line 2
    from typing import
                      ^
SyntaxError: invalid syntax"""
    ),
    (
            FLASK_INJECTION_METRIC,
            """  File "<string>", line 4, in <module>
ModuleNotFoundError: No module named 'flask'"""
    )
])
def test_invalid_code_returns_bad_request(client, code, stacktrace):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": code
    })
    assert response.status_code == 400
    assert response.json["error"] == f"400 Bad Request: Field 'code' contains invalid Python code: {stacktrace}"


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
    assert response.json[
               "error"] == f"400 Bad Request: The provided 'code' and 'data' fields can't be evaluated: {stacktrace}"


def test_no_scores_returns_bad_request(client):
    response = client.post(EVALUATORS_URL, json={
        "data": DATA,
        "code": MISSING_SCORE_METRIC
    })
    assert response.status_code == 400
    assert response.json[
               "error"] == "400 Bad Request: The provided 'code' field didn't return any 'opik.evaluation.metrics.ScoreResult'"

# TODO: Add test cases: timeout, networking etc.

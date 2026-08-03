import types

import pytest

import opik.exceptions as exceptions
import opik.guardrails.custom_training as custom_training


class _FakeApi:
    def __init__(self, statuses):
        self._statuses = list(statuses)
        self.train_called = None

    def train_custom(
        self, name, description, examples, base_model, epochs, overwrite=False
    ):
        self.train_called = {
            "name": name,
            "description": description,
            "examples": examples,
            "base_model": base_model,
            "epochs": epochs,
            "overwrite": overwrite,
        }
        return {"name": name, "status": "training"}

    def get_custom_training_status(self, name):
        return self._statuses.pop(0)


def _patch(monkeypatch, fake_api):
    fake_client = types.SimpleNamespace(
        config=types.SimpleNamespace(guardrails_backend_host="http://guardrails/")
    )
    monkeypatch.setattr(
        custom_training.opik_client, "get_global_client", lambda: fake_client
    )
    monkeypatch.setattr(
        custom_training.rest_api_client,
        "GuardrailsApiClient",
        lambda **kwargs: fake_api,
    )
    monkeypatch.setattr(custom_training.time, "sleep", lambda seconds: None)


def test_create_custom_guardrail__no_wait_returns_immediately(monkeypatch):
    fake = _FakeApi([])
    _patch(monkeypatch, fake)

    result = custom_training.create_custom_guardrail(
        "toxicity-v1",
        "contains toxic language",
        [{"text": "x", "label": 1}],
        wait=False,
    )

    assert result == {"name": "toxicity-v1", "status": "training"}
    assert fake.train_called["name"] == "toxicity-v1"
    assert fake.train_called["examples"] == [{"text": "x", "label": 1}]


def test_create_custom_guardrail__waits_for_completion(monkeypatch):
    fake = _FakeApi(
        [
            {"status": "training"},
            {"status": "completed", "eval_metrics": {"eval_test_f1": 0.9}},
        ]
    )
    _patch(monkeypatch, fake)

    result = custom_training.create_custom_guardrail(
        "toxicity-v1",
        "contains toxic language",
        [{"text": "x", "label": 1}],
        poll_interval=0.01,
    )

    assert result["status"] == "completed"
    assert result["eval_metrics"]["eval_test_f1"] == 0.9


def test_create_custom_guardrail__raises_on_failure(monkeypatch):
    fake = _FakeApi([{"status": "failed", "error": "boom"}])
    _patch(monkeypatch, fake)

    with pytest.raises(exceptions.GuardrailTrainingError):
        custom_training.create_custom_guardrail(
            "toxicity-v1", "contains toxic language", [{"text": "x", "label": 1}]
        )


def test_create_custom_guardrail__invokes_callback_with_progress(monkeypatch):
    fake = _FakeApi(
        [
            {"status": "training", "progress": {"percent": 40, "epoch": 1.0}},
            {"status": "training", "progress": {"percent": 80, "epoch": 2.0}},
            {"status": "completed", "eval_metrics": {"eval_test_f1": 0.9}},
        ]
    )
    _patch(monkeypatch, fake)

    seen = []
    custom_training.create_custom_guardrail(
        "toxicity-v1",
        "contains toxic language",
        [{"text": "x", "label": 1}],
        poll_interval=0.01,
        callback=lambda status: seen.append(status.get("status")),
    )

    assert seen == ["training", "training", "completed"]


def test_create_custom_guardrail__passes_overwrite(monkeypatch):
    fake = _FakeApi([])
    _patch(monkeypatch, fake)

    custom_training.create_custom_guardrail(
        "toxicity-v1",
        "contains toxic language",
        [{"text": "x", "label": 1}],
        overwrite=True,
        wait=False,
    )

    assert fake.train_called["overwrite"] is True

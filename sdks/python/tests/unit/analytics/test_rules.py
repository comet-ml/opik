import pytest

from opik import config
from opik.analytics import rules


def _config(**overrides):
    values = {"analytics_enable": True}
    values.update(overrides)
    return config.OpikConfig(**values)


@pytest.fixture
def outside_pytest(monkeypatch):
    """Analytics is off under pytest, which would short-circuit every rule test."""
    monkeypatch.setattr(rules.environment, "in_pytest", lambda: False)


def test_reporting_allowed__happyflow(outside_pytest):
    assert rules.reporting_allowed(_config()) is True


def test_reporting_allowed__running_tests__not_allowed():
    assert rules.reporting_allowed(_config()) is False


def test_reporting_allowed__disabled_in_config__not_allowed(outside_pytest):
    assert rules.reporting_allowed(_config(analytics_enable=False)) is False


def test_reporting_allowed__custom_rule_vetoes__not_allowed(
    outside_pytest, monkeypatch
):
    monkeypatch.setattr(rules, "_RULES", list(rules._RULES))
    rules.register_rule(lambda config_: False)

    assert rules.reporting_allowed(_config()) is False


def test_reporting_allowed__rule_raises__not_allowed(outside_pytest, monkeypatch):
    def broken_rule(config_):
        raise ValueError("boom")

    monkeypatch.setattr(rules, "_RULES", [broken_rule])

    assert rules.reporting_allowed(_config()) is False


def test_analytics_url__defaults_to_the_collector_the_backend_uses(monkeypatch):
    """The collector needs no credentials, so the default is all it takes."""
    monkeypatch.delenv("OPIK_ANALYTICS_URL", raising=False)

    assert config.OpikConfig().analytics_url == config.ANALYTICS_URL_DEFAULT

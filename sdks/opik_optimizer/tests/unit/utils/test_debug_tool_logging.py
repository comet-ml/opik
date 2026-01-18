import logging

from opik_optimizer.utils.logging import debug_tool_call


def test_debug_tool_call_redacts_secrets(caplog) -> None:
    caplog.set_level(logging.DEBUG, logger="opik_optimizer.debug")
    debug_tool_call(
        tool_name="search",
        arguments={"api_key": "secret", "query": "hello"},
        result={"token": "topsecret", "value": "ok"},
        tool_call_id="call_123",
    )
    assert "<REDACTED>" in caplog.text
    assert "secret" not in caplog.text
    assert "topsecret" not in caplog.text

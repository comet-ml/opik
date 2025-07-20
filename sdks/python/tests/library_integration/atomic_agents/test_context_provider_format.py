"""Test OpikContextProvider formatting."""

from opik.integrations.atomic_agents import context_providers


def test_provider_renders_correctly():
    provider = context_providers.OpikContextProvider(
        project_name="demo",
        trace_id="123",
    )
    rendered = provider.get_info()
    assert "demo" in rendered
    assert "123" in rendered
    assert "[Opik Trace Info]" in rendered

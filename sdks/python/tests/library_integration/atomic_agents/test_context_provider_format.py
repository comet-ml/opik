"""Test OpikContextProvider formatting."""

from opik.integrations.atomic_agents import OpikContextProvider


def test_get_info_format():  # noqa: D401
    provider = OpikContextProvider(project_name="proj", trace_id="abc123")
    info = provider.get_info()
    assert "[Opik Trace Info]" in info
    assert "trace_id: abc123" in info
    assert "project:  proj" in info 
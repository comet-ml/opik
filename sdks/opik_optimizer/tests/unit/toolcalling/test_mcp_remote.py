from opik_optimizer.utils.toolcalling import mcp_remote


def test_mcp_remote__load_sdk() -> None:
    session_cls, transport_cm = mcp_remote._load_remote_sdk()
    assert session_cls is not None
    assert transport_cm is not None

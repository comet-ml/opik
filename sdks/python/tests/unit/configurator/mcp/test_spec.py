from opik.configurator.mcp import spec as mcp_spec


def _stdio_spec():
    return mcp_spec.StdioServerSpec(
        command="/usr/bin/uvx",
        args=["opik-mcp"],
        env={"OPIK_API_KEY": "some-key", "COMET_WORKSPACE": "ws"},
    )


def test_stdio_server_spec__to_block__produces_stdio_json():
    block = _stdio_spec().to_block()
    assert block == {
        "type": "stdio",
        "command": "/usr/bin/uvx",
        "args": ["opik-mcp"],
        "env": {"OPIK_API_KEY": "some-key", "COMET_WORKSPACE": "ws"},
    }


def test_stdio_server_spec__to_claude_add_args__transport_env_and_command():
    args = _stdio_spec().to_claude_add_args()

    assert args[:3] == ["--transport", "stdio", mcp_spec.SERVER_NAME]
    assert "--env" in args
    assert "OPIK_API_KEY=some-key" in args

    separator_index = args.index("--")
    assert args[separator_index + 1 :] == ["/usr/bin/uvx", "opik-mcp"]


def test_remote_server_spec__to_block__produces_http_json():
    server_spec = mcp_spec.RemoteServerSpec(url="https://dev.comet.com/opik/api/v1/mcp")
    assert server_spec.to_block() == {
        "type": "http",
        "url": "https://dev.comet.com/opik/api/v1/mcp",
    }


def test_remote_server_spec__to_claude_add_args__http_transport_and_url():
    server_spec = mcp_spec.RemoteServerSpec(url="https://dev.comet.com/opik/api/v1/mcp")
    assert server_spec.to_claude_add_args() == [
        "--transport",
        "http",
        mcp_spec.SERVER_NAME,
        "https://dev.comet.com/opik/api/v1/mcp",
    ]


def test_redact_block_for_display__masks_secret_env_values():
    block = _stdio_spec().to_block()

    redacted = mcp_spec.redact_block_for_display(block)

    assert redacted["env"]["OPIK_API_KEY"] == "***REDACTED***"
    assert redacted["env"]["COMET_WORKSPACE"] == "ws"  # not a secret
    # original block is untouched (the real config write must keep the key)
    assert block["env"]["OPIK_API_KEY"] == "some-key"


def test_redact_block_for_display__no_env_is_passthrough():
    block = {"type": "http", "url": "https://example.com"}
    assert mcp_spec.redact_block_for_display(block) == block

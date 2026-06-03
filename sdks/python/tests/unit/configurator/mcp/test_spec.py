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

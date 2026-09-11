from typing import Any
import warnings

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling.runtime.mcp import ToolSignature
from opik_optimizer.utils.toolcalling.normalize.tool_factory import (
    _collect_function_names,
    ToolCallingFactory,
    _log_remote_tool_response,
    cursor_mcp_config_to_tools,
    resolve_toolcalling_tools,
)


def test_tool_factory__resolves_mcp_tools(monkeypatch: Any) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
        )

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)

    tools: list[dict[str, Any]] = [
        {
            "mcp": {
                "name": "context7.get-library-docs",
                "server": {
                    "type": "stdio",
                    "name": "context7-docs",
                    "command": "echo",
                    "args": [],
                    "env": {},
                },
                "tool": {"name": "get-library-docs"},
            }
        }
    ]

    resolved_tools, function_map = resolve_toolcalling_tools(tools, {})
    assert resolved_tools[0]["function"]["name"] == "context7.get-library-docs"
    assert resolved_tools[0]["function"]["description"] == "mcp tool"
    assert "context7.get-library-docs" in function_map

    prompt = ChatPrompt(system="sys", user="hello", tools=tools)
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    assert resolved_prompt.tools is not None
    assert getattr(resolved_prompt, "tools_original") == tools


def test_tool_factory__keeps_pre_resolved_tools(monkeypatch: Any) -> None:
    def _fake_callable(**_kwargs: Any) -> str:
        return "ok"

    monkeypatch.setattr(
        ToolCallingFactory, "_build_callable", lambda *_args, **_kw: _fake_callable
    )

    tool = {
        "type": "function",
        "function": {
            "name": "context7.get-library-docs",
            "description": "existing",
            "parameters": {"type": "object", "properties": {}},
        },
        "mcp": {
            "name": "context7.get-library-docs",
            "server": {
                "type": "stdio",
                "name": "context7-docs",
                "command": "echo",
                "args": [],
                "env": {},
            },
            "tool": {"name": "get-library-docs"},
        },
    }

    resolved_tools, function_map = resolve_toolcalling_tools([tool], {})
    assert resolved_tools[0]["function"]["description"] == "existing"
    assert "context7.get-library-docs" in function_map
    assert function_map["context7.get-library-docs"]() == "ok"


def test_tool_factory__require_approval_blocks_execution(monkeypatch: Any) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        _ = self, server, _signature_override
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
        )

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)

    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": ["search"],
            "require_approval": True,
        }
    ]

    _, function_map = resolve_toolcalling_tools(tools, {})
    blocked_name = next(iter(function_map.keys()))

    with pytest.raises(PermissionError, match="requires approval before execution"):
        function_map[blocked_name]()


def test_tool_factory__rejects_non_boolean_require_approval() -> None:
    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": ["search"],
            "require_approval": "false",
        }
    ]

    with pytest.raises(ValueError, match="require_approval must be a boolean"):
        resolve_toolcalling_tools(tools, {})


def test_tool_factory__does_not_warn_when_require_approval_false() -> None:
    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": [],
            "require_approval": False,
        }
    ]

    with warnings.catch_warnings(record=True) as warning_records:
        warnings.simplefilter("always")
        with pytest.raises(ValueError, match="did not return any tools"):
            resolve_toolcalling_tools(tools, {})
    assert not [
        record
        for record in warning_records
        if "blocked from execution until approved" in str(record.message)
    ]


def test_tool_factory__rejects_non_mapping_headers() -> None:
    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "headers": "bad-headers",
            "allowed_tools": ["search"],
        }
    ]

    with pytest.raises(ValueError, match="headers must be a mapping"):
        resolve_toolcalling_tools(tools, {})


def test_tool_factory__coerces_openai_style_headers_and_auth_to_strings(
    monkeypatch: Any,
) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        assert server["headers"] == {"X-NUM": "123", "X-NONE": ""}
        assert server["auth"] == {"token": "456"}
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
        )

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)

    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "headers": {"X-NUM": 123, "X-NONE": None},
            "auth": {"token": 456},
            "allowed_tools": ["search"],
        }
    ]

    resolved_tools, _function_map = resolve_toolcalling_tools(tools, {})
    assert resolved_tools[0]["mcp"]["server"]["headers"]["X-NUM"] == "123"
    assert resolved_tools[0]["mcp"]["server"]["headers"]["X-NONE"] == ""
    assert resolved_tools[0]["mcp"]["server"]["auth"]["token"] == "456"


def test_tool_factory__redacts_sensitive_meta_in_debug_log(caplog: Any) -> None:
    class FakeResponse:
        meta = {
            "status": 429,
            "headers": {"authorization": "Bearer secret-token"},
            "auth": {"password": "p@ss"},
            "request_id": "req-123",
        }
        content: list[Any] = []

    with caplog.at_level(
        "DEBUG", logger="opik_optimizer.utils.toolcalling.normalize.tool_factory"
    ):
        _log_remote_tool_response("search", FakeResponse())

    assert "secret-token" not in caplog.text
    assert "p@ss" not in caplog.text
    assert "***REDACTED***" in caplog.text


def test_tool_factory__cursor_env_mapping_coerces_values_to_strings(
    monkeypatch: Any,
) -> None:
    monkeypatch.setenv("API_KEY", "from-env")
    tools = cursor_mcp_config_to_tools(
        {
            "mcpServers": {
                "ctx": {
                    "url": "https://mcp.context7.com/mcp",
                    "headers": {
                        "X-NUM": 123,
                        "Authorization": "${env:API_KEY}",
                        "X-EMPTY": "",
                    },
                    "auth": {"token": None},
                }
            }
        }
    )

    headers = tools[0]["headers"]
    auth = tools[0]["auth"]
    assert headers["X-NUM"] == "123"
    assert headers["Authorization"] == "from-env"
    assert isinstance(headers["X-EMPTY"], str)
    assert auth["token"] == "None"


def test_tool_factory__avoids_name_collisions(monkeypatch: Any) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
        )

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)

    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": ["search"],
        },
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "existing",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]

    resolved_tools, _function_map = resolve_toolcalling_tools(tools, {})
    names = [tool.get("function", {}).get("name") for tool in resolved_tools]
    assert "search" in names
    assert "context7.search" in names


def test_tool_factory__resolves_remote_mcp_tool(monkeypatch: Any) -> None:
    class FakeTool:
        def __init__(self, name: str, description: str, input_schema: dict[str, Any]):
            self.name = name
            self.description = description
            self.inputSchema = input_schema

        def model_dump(self, by_alias: bool = False) -> dict[str, Any]:
            return {
                "name": self.name,
                "description": self.description,
                "inputSchema": self.inputSchema,
                "annotations": {},
            }

    def _fake_list_tools(
        url: str, headers: dict[str, str], auth: dict[str, str] | None
    ) -> list[FakeTool]:
        assert url == "https://mcp.context7.com/mcp"
        assert headers == {"CONTEXT7_API_KEY": "YOUR_API_KEY"}
        assert auth == {"token": "abc123"}
        return [
            FakeTool(
                name="get-library-docs",
                description="remote docs tool",
                input_schema={
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                },
            )
        ]

    class FakeResponse:
        def __init__(self, output: str) -> None:
            self.output = output

    def _fake_call_tool(
        url: str,
        headers: dict[str, str],
        auth: dict[str, str] | None,
        tool_name: str,
        arguments: dict[str, Any],
    ) -> FakeResponse:
        assert tool_name == "get-library-docs"
        assert arguments == {"query": "opik"}
        assert auth == {"token": "abc123"}
        return FakeResponse("docs response")

    monkeypatch.setattr(
        "opik_optimizer.utils.toolcalling.normalize.tool_factory.list_tools_from_remote",
        _fake_list_tools,
    )
    monkeypatch.setattr(
        "opik_optimizer.utils.toolcalling.normalize.tool_factory.call_tool_from_remote",
        _fake_call_tool,
    )

    tools = [
        {
            "mcp": {
                "name": "context7.get-library-docs",
                "server": {
                    "type": "remote",
                    "url": "https://mcp.context7.com/mcp",
                    "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"},
                    "auth": {"token": "abc123"},
                },
                "tool": {"name": "get-library-docs"},
            }
        }
    ]

    prompt = ChatPrompt(system="sys", user="hello", tools=tools)
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    assert resolved_prompt.tools is not None
    assert resolved_prompt.tools[0]["function"]["description"] == "remote docs tool"

    _, function_map = resolve_toolcalling_tools(resolved_prompt.tools, {})
    assert function_map["context7.get-library-docs"](query="opik") == "docs response"


def test_tool_factory__collect_names_skips_non_mapping_tools() -> None:
    names = _collect_function_names(
        [
            "not-a-dict",
            {"type": "function", "function": {"name": "search"}},
        ],
        {"existing": lambda: None},
    )
    assert names == {"existing", "search"}


def test_tool_factory__registers_base_alias_for_renamed_mcp_tool(
    monkeypatch: Any,
) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        _ = self, server, _signature_override
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
        )

    def _fake_build_callable(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        output_schema: dict[str, Any] | None = None,
    ) -> Any:
        _ = self, server, output_schema
        return lambda **_kwargs: f"called:{tool_name}"

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)
    monkeypatch.setattr(ToolCallingFactory, "_build_callable", _fake_build_callable)

    tools: list[dict[str, Any]] = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "existing schema-only tool",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": ["search"],
        },
    ]

    _resolved_tools, function_map = resolve_toolcalling_tools(tools, {})
    assert "context7.search" in function_map
    assert "search" in function_map
    assert function_map["context7.search"]() == "called:search"
    assert function_map["search"]() == "called:search"


def test_tool_factory__validates_tool_output_schema_override_success(
    monkeypatch: Any,
) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        _ = self, server, _signature_override
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
            extra={
                "output_schema": {
                    "type": "object",
                    "required": ["answer"],
                    "properties": {"answer": {"type": "string"}},
                    "additionalProperties": False,
                }
            },
        )

    def _fake_call_tool(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        arguments: dict[str, Any],
    ) -> dict[str, Any]:
        _ = self, server, tool_name, arguments
        return {"answer": "ok"}

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)
    monkeypatch.setattr(ToolCallingFactory, "_call_tool", _fake_call_tool)

    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": ["search"],
        }
    ]
    resolved_tools, function_map = resolve_toolcalling_tools(tools, {})

    assert (
        resolved_tools[0].get("mcp", {}).get("output_schema", {}).get("type")
        == "object"
    )
    assert function_map["search"]() == "{'answer': 'ok'}"


def test_tool_factory__validates_tool_output_schema_override_failure(
    monkeypatch: Any,
) -> None:
    def _fake_get_signature(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        _signature_override: dict[str, Any] | None,
    ) -> ToolSignature:
        _ = self, server, _signature_override
        return ToolSignature(
            name=tool_name,
            description="mcp tool",
            parameters={"type": "object", "properties": {}},
            extra={
                "output_schema": {
                    "type": "object",
                    "required": ["answer"],
                    "properties": {"answer": {"type": "string"}},
                    "additionalProperties": False,
                }
            },
        )

    def _fake_call_tool(
        self: ToolCallingFactory,
        server: dict[str, Any],
        tool_name: str,
        arguments: dict[str, Any],
    ) -> dict[str, Any]:
        _ = self, server, tool_name, arguments
        return {"wrong": 1}

    monkeypatch.setattr(ToolCallingFactory, "_get_signature", _fake_get_signature)
    monkeypatch.setattr(ToolCallingFactory, "_call_tool", _fake_call_tool)

    tools: list[dict[str, Any]] = [
        {
            "type": "mcp",
            "server_label": "context7",
            "server_url": "https://mcp.context7.com/mcp",
            "allowed_tools": ["search"],
        }
    ]
    _, function_map = resolve_toolcalling_tools(tools, {})

    with pytest.raises(
        ValueError, match="output failed schema validation: \\$.answer is required"
    ):
        function_map["search"]()

"""Helpers for working with MCP tool signatures in optimization flows."""

from __future__ import annotations

import asyncio
import copy
import importlib
import inspect
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Tuple


TOOL_ENTRY_KEY = "function"


@dataclass
class ToolSignature:
    """Representation of an MCP tool definition used for tuning."""

    name: str
    description: str
    parameters: Mapping[str, Any]
    examples: Optional[List[Dict[str, Any]]] = None
    extra: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_tool_entry(cls, entry: Mapping[str, Any]) -> "ToolSignature":
        if TOOL_ENTRY_KEY not in entry:
            raise ValueError("Tool entry missing 'function' block")

        function_block = entry[TOOL_ENTRY_KEY]
        name = function_block.get("name")
        if not name:
            raise ValueError("Tool entry missing function name")

        description = function_block.get("description", "")
        parameters = function_block.get("parameters", {}) or {}
        examples = function_block.get("examples")
        extra = {
            key: value
            for key, value in entry.items()
            if key not in {TOOL_ENTRY_KEY}
        }

        return cls(
            name=name,
            description=description,
            parameters=parameters,
            examples=examples,
            extra=extra,
        )

    def to_tool_entry(self) -> Dict[str, Any]:
        entry = copy.deepcopy(self.extra)
        entry.update(
            {
                TOOL_ENTRY_KEY: {
                    "name": self.name,
                    "description": self.description,
                    "parameters": self.parameters,
                }
            }
        )
        if self.examples is not None:
            entry[TOOL_ENTRY_KEY]["examples"] = self.examples
        return entry

    def segment_update(self) -> Tuple[str, str]:
        return (f"tool:{self.name}", self.description)


def load_mcp_signature(path: Path) -> List[ToolSignature]:
    data = json.loads(Path(path).read_text())

    if isinstance(data, dict) and "tools" in data:
        data = data["tools"]

    if not isinstance(data, list):
        raise ValueError("MCP signature file must contain a list of tools")

    return [ToolSignature.from_tool_entry(entry) for entry in data]


def dump_mcp_signature(signatures: Iterable[ToolSignature], path: Path) -> None:
    payload = [signature.to_tool_entry() for signature in signatures]
    Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True))


def tools_from_signatures(signatures: Iterable[ToolSignature]) -> List[Dict[str, Any]]:
    return [signature.to_tool_entry() for signature in signatures]


def signature_updates(signatures: Iterable[ToolSignature]) -> Dict[str, str]:
    return dict(signature.segment_update() for signature in signatures)


def validate_tool_arguments(
    signature: ToolSignature, arguments: Mapping[str, Any]
) -> Tuple[bool, str]:
    """Validate ``arguments`` against required fields in the signature schema."""

    schema_required = signature.parameters.get("required", [])
    for field in schema_required:
        if field not in arguments:
            return False, f"Missing required argument '{field}'"

    properties = signature.parameters.get("properties", {})
    for key, value in arguments.items():
        prop_schema = properties.get(key)
        if not prop_schema:
            continue
        expected_type = prop_schema.get("type")
        if expected_type:
            if expected_type == "string" and not isinstance(value, str):
                return False, f"Argument '{key}' must be a string"
            if expected_type == "number" and not isinstance(value, (int, float)):
                return False, f"Argument '{key}' must be a number"
            if expected_type == "integer" and not isinstance(value, int):
                return False, f"Argument '{key}' must be an integer"
            if expected_type == "boolean" and not isinstance(value, bool):
                return False, f"Argument '{key}' must be a boolean"

    return True, ""


# ---------------------------------------------------------------------------
# MCP runtime helpers using the official Python SDK


class MCPDependencyError(RuntimeError):
    """Raised when the Model Context Protocol SDK is unavailable."""


def _load_sdk():
    candidates = (
        ("modelcontextprotocol.client.session", "modelcontextprotocol.client.transport"),
        ("mcp.client.session", "mcp.client.transport"),
    )

    for session_path, transport_path in candidates:
        try:
            session_mod = importlib.import_module(session_path)
            transport_mod = importlib.import_module(transport_path)
        except ImportError:
            continue

        session_cls = getattr(session_mod, "ClientSession", None) or getattr(
            session_mod, "Session", None
        )
        transport_cls = getattr(transport_mod, "SubprocessTransport", None) or getattr(
            transport_mod, "StdioTransport", None
        )

        if session_cls and transport_cls:
            return session_cls, transport_cls

    raise MCPDependencyError(
        "modelcontextprotocol Python SDK not found. Install it with 'pip install modelcontextprotocol'."
    )


try:
    ClientSession, TransportCls = _load_sdk()
    _SDK_ERROR: Optional[Exception] = None
except MCPDependencyError as exc:  # pragma: no cover
    ClientSession = None  # type: ignore[assignment]
    TransportCls = None  # type: ignore[assignment]
    _SDK_ERROR = exc


@dataclass
class MCPManifest:
    name: str
    command: str
    args: List[str]
    env: Dict[str, str]

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> "MCPManifest":
        command = data.get("command")
        if not command:
            raise ValueError("mcp.json missing 'command'")
        return cls(
            name=data.get("name", "mcp-server"),
            command=command,
            args=data.get("args", []),
            env=data.get("env", {}),
        )

    @classmethod
    def from_json(cls, path: Path) -> "MCPManifest":
        return cls.from_dict(json.loads(Path(path).read_text()))


class MCPClient:
    def __init__(self, manifest: MCPManifest):
        if _SDK_ERROR is not None:
            raise MCPDependencyError(str(_SDK_ERROR))
        self.manifest = manifest
        self._transport = None
        self._session: Optional[ClientSession] = None

    async def __aenter__(self):
        transport_kwargs: Dict[str, Any] = {}
        command_list = [self.manifest.command, *self.manifest.args]
        try:
            init_sig = inspect.signature(TransportCls)  # type: ignore[arg-type]
            params = init_sig.parameters
            if "command" in params and "args" in params:
                transport_kwargs["command"] = self.manifest.command
                transport_kwargs["args"] = self.manifest.args
            else:
                transport_kwargs["command"] = command_list
            if "env" in params:
                transport_kwargs["env"] = self.manifest.env or None
        except Exception:  # pragma: no cover
            transport_kwargs["command"] = command_list
            transport_kwargs["env"] = self.manifest.env or None

        self._transport = TransportCls(**transport_kwargs)
        self._session = ClientSession(self._transport)

        if hasattr(self._session, "__aenter__"):
            await self._session.__aenter__()
        elif hasattr(self._session, "start"):
            await self._session.start()

        if hasattr(self._session, "initialize"):
            await self._session.initialize()
        return self

    async def __aexit__(self, exc_type, exc, tb):
        if self._session is not None:
            if hasattr(self._session, "__aexit__"):
                await self._session.__aexit__(exc_type, exc, tb)
            elif hasattr(self._session, "close"):
                await self._session.close()
        if self._transport is not None and hasattr(self._transport, "close"):
            await self._transport.close()

    async def list_tools(self):
        if self._session is None:
            raise RuntimeError("MCP session not started")
        if hasattr(self._session, "list_tools"):
            response = await self._session.list_tools()
            return getattr(response, "tools", response)
        if hasattr(self._session, "tools"):
            return await self._session.tools()
        raise RuntimeError("MCP session missing list_tools")

    async def get_tool(self, tool_name: str):
        tools = await self.list_tools()
        for tool in tools:
            if tool.name == tool_name:
                return tool
        raise ValueError(f"Tool '{tool_name}' not found")

    async def call_tool(self, tool_name: str, arguments: Mapping[str, Any]):
        if self._session is None:
            raise RuntimeError("MCP session not started")
        if hasattr(self._session, "call_tool"):
            return await self._session.call_tool(tool_name=tool_name, arguments=arguments)
        if hasattr(self._session, "invoke_tool"):
            return await self._session.invoke_tool(tool_name, arguments)
        raise RuntimeError("MCP session missing call_tool")


def run_sync(coro):
    return asyncio.run(coro)


def list_tools_from_manifest(manifest: MCPManifest):
    async def _inner():
        async with MCPClient(manifest) as client:
            return await client.list_tools()

    return run_sync(_inner())


def call_tool_from_manifest(manifest: MCPManifest, tool_name: str, arguments: Dict[str, Any]):
    async def _inner():
        async with MCPClient(manifest) as client:
            return await client.call_tool(tool_name, arguments)

    return run_sync(_inner())


def response_to_text(response: object) -> str:
    if hasattr(response, "content"):
        return str(getattr(response, "content"))
    if hasattr(response, "output"):
        return str(getattr(response, "output"))
    return str(response)


PROMPT_TOOL_HEADER = "<<TOOL_DESCRIPTION>>"
PROMPT_TOOL_FOOTER = "<<END_TOOL_DESCRIPTION>>"


def system_prompt_from_tool(signature: ToolSignature) -> str:
    parameters = signature.parameters or {}
    parameter_lines = []
    for name, schema in parameters.get("properties", {}).items():
        type_hint = schema.get("type", "any")
        desc = schema.get("description", "")
        parameter_lines.append(f"- {name} ({type_hint}): {desc}")
    parameter_section = "\n".join(parameter_lines) if parameter_lines else "- No structured parameters."

    return (
        "You are an assistant that answers developer questions using the available MCP tool.\n"
        "Always decide whether the tool is required before answering.\n"
        "\n"
        "Tool description:\n"
        f"{PROMPT_TOOL_HEADER}\n{signature.description}\n{PROMPT_TOOL_FOOTER}\n"
        "\n"
        "Tool parameters:\n"
        f"{parameter_section}\n"
        "When you call the tool, read its response carefully before replying."
    )


def extract_description_from_system(system_prompt: str) -> Optional[str]:
    if PROMPT_TOOL_HEADER not in system_prompt or PROMPT_TOOL_FOOTER not in system_prompt:
        return None
    start = system_prompt.index(PROMPT_TOOL_HEADER) + len(PROMPT_TOOL_HEADER)
    end = system_prompt.index(PROMPT_TOOL_FOOTER)
    return system_prompt[start:end].strip()


def load_tool_signature_from_manifest(manifest: MCPManifest, tool_name: str) -> ToolSignature:
    tools = list_tools_from_manifest(manifest)
    tool = next((tool for tool in tools if tool.name == tool_name), None)
    if tool is None:
        raise ValueError(f"Tool '{tool_name}' not found")
    function_block = getattr(tool, "to_dict", lambda: tool)()
    if isinstance(function_block, dict) and "function" in function_block:
        return ToolSignature.from_tool_entry(function_block)
    # Some SDKs expose attributes instead of dicts
    entry = {
        "type": "function",
        "function": {
            "name": getattr(tool, "name", tool_name),
            "description": getattr(tool, "description", ""),
            "parameters": getattr(tool, "input_schema", {}),
            "examples": getattr(tool, "examples", None),
        },
    }
    return ToolSignature.from_tool_entry(entry)


def score_query_tool(
    manifest: MCPManifest,
    tool_name: str,
    dataset: Iterable[Mapping[str, Any]],
    description: str,
    argument_key: str = "query",
) -> float:
    successes = 0
    total = 0
    description_tokens = set(description.lower().split())
    for record in dataset:
        arguments = record.get("arguments", {})
        value = arguments.get(argument_key, "")
        if not value:
            continue
        total += 1
        value_tokens = set(value.lower().split())
        if description_tokens.isdisjoint(value_tokens):
            continue
        response = call_tool_from_manifest(manifest, tool_name, arguments)
        text = response_to_text(response).lower()
        if record.get("expected_answer_contains", "").lower() in text:
            successes += 1
    return successes / total if total else 0.0


def score_url_tool(
    manifest: MCPManifest,
    tool_name: str,
    dataset: Iterable[Mapping[str, Any]],
    description: str,
    argument_key: str = "url",
) -> float:
    from urllib.parse import urlparse

    successes = 0
    total = 0
    description_tokens = set(description.lower().split())
    for record in dataset:
        arguments = record.get("arguments", {})
        url = arguments.get(argument_key, "")
        if not url:
            continue
        total += 1
        host_tokens = set(urlparse(url).netloc.lower().split("."))
        if description_tokens.isdisjoint(host_tokens):
            continue
        response = call_tool_from_manifest(manifest, tool_name, arguments)
        text = response_to_text(response).lower()
        if record.get("expected_answer_contains", "").lower() in text:
            successes += 1
    return successes / total if total else 0.0

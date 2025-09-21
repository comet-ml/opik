"""Helpers for working with MCP tool signatures in optimization flows."""

from __future__ import annotations

import asyncio
import copy
import importlib
import json
import textwrap
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
        (
            "mcp.client.session",
            "mcp.client.stdio",
            "mcp.types",
        ),
        (
            "modelcontextprotocol.client.session",
            "modelcontextprotocol.client.stdio",
            "modelcontextprotocol.types",
        ),
    )

    for session_path, stdio_path, types_path in candidates:
        try:
            session_mod = importlib.import_module(session_path)
            stdio_mod = importlib.import_module(stdio_path)
            types_mod = importlib.import_module(types_path)
        except ImportError:
            continue

        session_cls = getattr(session_mod, "ClientSession", None)
        stdio_client_fn = getattr(stdio_mod, "stdio_client", None)
        stdio_params_cls = getattr(stdio_mod, "StdioServerParameters", None)

        if session_cls and stdio_client_fn and stdio_params_cls:
            return session_cls, stdio_client_fn, stdio_params_cls, types_mod

    raise MCPDependencyError(
        "modelcontextprotocol Python SDK not found. Install it with 'pip install mcp'."
    )


try:
    (ClientSession, StdioClientFactory, StdioServerParameters, types_mod) = _load_sdk()
    _SDK_ERROR: Optional[Exception] = None
except MCPDependencyError as exc:  # pragma: no cover
    ClientSession = None  # type: ignore[assignment]
    StdioClientFactory = None  # type: ignore[assignment]
    StdioServerParameters = None  # type: ignore[assignment]
    types_mod = None  # type: ignore[assignment]
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
        self._transport_cm = None
        self._session: Optional[ClientSession] = None
        self._read_stream = None
        self._write_stream = None

    async def __aenter__(self):
        server_params = StdioServerParameters(  # type: ignore[arg-type]
            command=self.manifest.command,
            args=self.manifest.args,
            env=self.manifest.env or None,
        )

        self._transport_cm = StdioClientFactory(server_params)
        self._read_stream, self._write_stream = await self._transport_cm.__aenter__()
        self._session = ClientSession(self._read_stream, self._write_stream)

        if hasattr(self._session, "__aenter__"):
            await self._session.__aenter__()

        if hasattr(self._session, "initialize"):
            await self._session.initialize()
        return self

    async def __aexit__(self, exc_type, exc, tb):
        if self._session is not None:
            if hasattr(self._session, "__aexit__"):
                await self._session.__aexit__(exc_type, exc, tb)
        if self._transport_cm is not None:
            await self._transport_cm.__aexit__(exc_type, exc, tb)

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
        return await self._session.call_tool(name=tool_name, arguments=arguments)


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
        content = getattr(response, "content")
        if isinstance(content, list):
            texts = []
            for item in content:
                text_value = getattr(item, "text", None)
                if text_value:
                    texts.append(text_value)
            if texts:
                return "\n".join(texts)
        return str(content)
    if hasattr(response, "output"):
        return str(getattr(response, "output"))
    return str(response)


PROMPT_TOOL_HEADER = "<<TOOL_DESCRIPTION>>"
PROMPT_TOOL_FOOTER = "<<END_TOOL_DESCRIPTION>>"

# System-prompt scaffolding below is inspired by the MCP section of Cline's
# system prompt (Apache-2.0). See https://github.com/cline/cline for details.
TOOL_USE_GUIDELINES = textwrap.dedent(
    """
    # Tool Use Guidelines

    1. In <thinking> tags, decide what you already know and what information you still need.
    2. Choose the best tool for the current step using the descriptions and schemas provided.
    3. Use one tool call per message, wait for its result, then decide the next step.
    4. Format tool calls exactly with the XML shown in the tool examples.
    5. After each tool call, read the result carefully before responding or calling another tool.
    6. Always incorporate the tool output into your final answer.
    """
).strip()


def _format_json_block(data: Mapping[str, Any]) -> str:
    return textwrap.indent(json.dumps(data, indent=2), "    ")


def system_prompt_from_tool(signature: ToolSignature, manifest: Optional[MCPManifest] = None) -> str:
    parameters = signature.parameters or {}
    parameter_lines = []
    for name, schema in parameters.get("properties", {}).items():
        type_hint = schema.get("type", "any")
        desc = schema.get("description", "")
        parameter_lines.append(f"- {name} ({type_hint}): {desc}")
    parameter_section = "\n".join(parameter_lines) if parameter_lines else "- No structured parameters."

    mcp_header = ""
    if manifest is not None:
        command_line = manifest.command
        if manifest.args:
            command_line = f"{command_line} {' '.join(manifest.args)}"

        schema_block = _format_json_block(signature.parameters) if signature.parameters else "    {}"

        mcp_header = textwrap.dedent(
            f"""
            MCP SERVERS

            The Model Context Protocol (MCP) enables communication between the system and locally running MCP servers that provide additional tools and resources to extend your capabilities.

            # Connected MCP Servers

            When a server is connected, you can use the server's tools via the `use_mcp_tool` tool, and access the server's resources via the `access_mcp_resource` tool.

            ## {manifest.name} (`{command_line}`)

            ### Available Tools
            - {signature.name}: {signature.description}
                Input Schema:
{schema_block}
            """
        ).strip()

    body = textwrap.dedent(
        f"""
        You are an assistant that answers developer questions using the available MCP tool.
        Always decide whether the tool is required before answering.
        Always call the tool at least once before replying and incorporate the returned documentation into your answer (quote key terms, mention the library ID).

        Tool description:
        {PROMPT_TOOL_HEADER}
        {signature.description}
        {PROMPT_TOOL_FOOTER}

        Tool parameters:
        {parameter_section}
        When you call the tool, read its response carefully before replying.
        """
    ).strip()

    sections = [mcp_header, TOOL_USE_GUIDELINES, body]
    return "\n\n".join(section for section in sections if section).strip()


def extract_description_from_system(system_prompt: str) -> Optional[str]:
    if PROMPT_TOOL_HEADER not in system_prompt or PROMPT_TOOL_FOOTER not in system_prompt:
        return None
    start = system_prompt.index(PROMPT_TOOL_HEADER) + len(PROMPT_TOOL_HEADER)
    end = system_prompt.index(PROMPT_TOOL_FOOTER)
    return system_prompt[start:end].strip()


def load_tool_signature_from_manifest(manifest: MCPManifest, tool_name: str) -> ToolSignature:
    tools = list_tools_from_manifest(manifest)
    tool = next((tool for tool in tools if getattr(tool, "name", None) == tool_name), None)
    if tool is None:
        raise ValueError(f"Tool '{tool_name}' not found")
    entry = tool.model_dump(by_alias=True)
    annotations = entry.get("annotations") or {}
    examples = annotations.get("examples")
    return ToolSignature.from_tool_entry(
        {
            "type": "function",
            "function": {
                "name": entry.get("name", tool_name),
                "description": entry.get("description", ""),
                "parameters": entry.get("inputSchema", {}),
                "examples": examples,
            },
        }
    )


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

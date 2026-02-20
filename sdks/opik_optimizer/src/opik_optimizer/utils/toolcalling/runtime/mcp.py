"""Helpers for working with MCP tool signatures in optimization flows.

This module focuses on stdio-based MCP servers (local subprocesses) and provides
signature helpers used by tool factories. Remote servers are handled in
``mcp_remote`` and accessed via ``tool_factory``.
"""

from __future__ import annotations

import asyncio
import copy
import importlib
import json
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import Path
from types import TracebackType
from typing import Any, TypeVar, cast
from collections.abc import Coroutine, Iterable, Mapping

from ..ops import prompts as mcp_prompts
from .session_pool import SessionPool
from ... import throttle as _throttle

ClientSession: type[Any] | None = None
StdioClientFactory: type[Any] | None = None
StdioServerParameters: type[Any] | None = None
types_mod: Any | None = None

_T = TypeVar("_T")
_toolcalling_limiter = _throttle.get_toolcalling_rate_limiter()
_POOL = SessionPool()

TOOL_ENTRY_KEY = "function"


def _manifest_key(manifest: ToolCallingManifest) -> str:
    """Return a stable cache key for a manifest."""
    payload = {
        "command": manifest.command,
        "args": manifest.args,
        "env": manifest.env,
        "name": manifest.name,
    }
    return json.dumps(payload, sort_keys=True, default=str)


async def _get_or_create_client(
    manifest: ToolCallingManifest,
) -> ToolCallingClient:
    """Return a cached client for a manifest, creating if needed."""
    key = _manifest_key(manifest)
    return await _POOL.get_or_create(key, lambda: _start_client(manifest))


async def _start_client(manifest: ToolCallingManifest) -> ToolCallingClient:
    """Start a new ToolCallingClient for a manifest."""
    client = ToolCallingClient(manifest)
    await client.__aenter__()
    return client


async def _close_client(client: ToolCallingClient) -> None:
    """Close a ToolCallingClient instance."""
    await client.__aexit__(None, None, None)


_POOL.set_closer(lambda client: _close_client(client))


@dataclass
class ToolSignature:
    """Representation of an MCP tool definition used for tuning."""

    name: str
    description: str
    parameters: Mapping[str, Any]
    examples: list[dict[str, Any]] | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_tool_entry(cls, entry: Mapping[str, Any]) -> ToolSignature:
        """Build a ToolSignature from a function tool entry."""
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
            key: value for key, value in entry.items() if key not in {TOOL_ENTRY_KEY}
        }

        return cls(
            name=name,
            description=description,
            parameters=parameters,
            examples=examples,
            extra=extra,
        )

    def to_tool_entry(self) -> dict[str, Any]:
        """Return a function tool entry for this signature."""
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

    def segment_update(self) -> tuple[str, str]:
        """Return the prompt segment id + description for this tool."""
        return (f"tool:{self.name}", self.description)


def load_mcp_signature(path: Path) -> list[ToolSignature]:
    """Load a list of tool signatures from a JSON file."""
    data = json.loads(Path(path).read_text())

    if isinstance(data, dict) and "tools" in data:
        data = data["tools"]

    if not isinstance(data, list):
        raise ValueError("MCP signature file must contain a list of tools")

    return [ToolSignature.from_tool_entry(entry) for entry in data]


def dump_mcp_signature(signatures: Iterable[ToolSignature], path: Path) -> None:
    """Write tool signatures to disk as JSON."""
    payload = [signature.to_tool_entry() for signature in signatures]
    Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True))


def tools_from_signatures(signatures: Iterable[ToolSignature]) -> list[dict[str, Any]]:
    """Convert tool signatures to function tool entries."""
    return [signature.to_tool_entry() for signature in signatures]


def signature_updates(signatures: Iterable[ToolSignature]) -> dict[str, str]:
    """Return a mapping of segment ids to descriptions."""
    return dict(signature.segment_update() for signature in signatures)


def validate_tool_arguments(
    signature: ToolSignature, arguments: Mapping[str, Any]
) -> tuple[bool, str]:
    """Validate ``arguments`` against required fields in the signature schema."""

    schema_required = signature.parameters.get("required", [])
    for required_field in schema_required:
        if required_field not in arguments:
            return False, f"Missing required argument '{required_field}'"

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


def validate_tool_output(
    signature: ToolSignature,
    response: object,
) -> tuple[bool, str]:
    """Validate tool output against optional ``output_schema`` in the signature."""
    raw_schema = signature.extra.get("output_schema")
    if not isinstance(raw_schema, Mapping):
        return True, ""
    output_data = _extract_tool_output_for_validation(response)
    return _validate_json_schema_value(output_data, raw_schema, path="$")


def _extract_tool_output_for_validation(response: object) -> Any:
    """Extract structured data from MCP responses for output-schema validation."""
    if hasattr(response, "output"):
        output = getattr(response, "output")
        if output is not None:
            return output
    if hasattr(response, "content"):
        content = getattr(response, "content")
        if isinstance(content, list):
            if len(content) == 1:
                item = content[0]
                if hasattr(item, "json"):
                    json_value = getattr(item, "json")
                    if json_value is not None:
                        return json_value
                text_value = getattr(item, "text", None)
                if isinstance(text_value, str):
                    try:
                        return json.loads(text_value)
                    except json.JSONDecodeError:
                        return text_value
            return response_to_text(response)
        return content
    return response


def _validate_json_schema_value(
    value: Any,
    schema: Mapping[str, Any],
    *,
    path: str,
) -> tuple[bool, str]:
    """Validate a value against a lightweight JSON-schema subset."""
    ok, err = _validate_any_of(value, schema, path=path)
    if not ok:
        return False, err

    ok, err = _validate_type_constraint(value, schema, path=path)
    if not ok:
        return False, err

    if isinstance(value, Mapping):
        ok, err = _validate_object_schema(value, schema, path=path)
        if not ok:
            return False, err

    if isinstance(value, list):
        ok, err = _validate_array_schema(value, schema, path=path)
        if not ok:
            return False, err

    return True, ""


def _validate_any_of(
    value: Any, schema: Mapping[str, Any], *, path: str
) -> tuple[bool, str]:
    """Validate ``anyOf`` clauses when present."""
    any_of = schema.get("anyOf")
    if not isinstance(any_of, list) or not any_of:
        return True, ""
    errors: list[str] = []
    for candidate in any_of:
        if not isinstance(candidate, Mapping):
            continue
        ok, err = _validate_json_schema_value(value, candidate, path=path)
        if ok:
            return True, ""
        errors.append(err)
    return False, errors[0] if errors else f"{path} did not match anyOf schema"


def _validate_type_constraint(
    value: Any, schema: Mapping[str, Any], *, path: str
) -> tuple[bool, str]:
    """Validate ``type`` constraints for a schema node."""
    allowed_types = _allowed_types_from_schema(schema)
    if not allowed_types:
        return True, ""
    if value is None and "null" in allowed_types:
        return True, ""
    value_type = _json_type_name(value)
    if value_type == "integer" and "number" in allowed_types:
        return True, ""
    if value_type in allowed_types:
        return True, ""
    return False, f"{path} expected type {sorted(allowed_types)} but got {value_type}"


def _allowed_types_from_schema(schema: Mapping[str, Any]) -> set[str]:
    """Return normalized allowed type names from schema."""
    raw_type = schema.get("type")
    if isinstance(raw_type, str):
        return {raw_type}
    if isinstance(raw_type, list):
        return {item for item in raw_type if isinstance(item, str)}
    return set()


def _json_type_name(value: Any) -> str:
    """Return the JSON-schema type name for a value."""
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, Mapping):
        return "object"
    return "unknown"


def _validate_object_schema(
    value: Mapping[str, Any], schema: Mapping[str, Any], *, path: str
) -> tuple[bool, str]:
    """Validate object-specific schema keys."""
    required = schema.get("required")
    if isinstance(required, list):
        for key in required:
            if isinstance(key, str) and key not in value:
                return False, f"{path}.{key} is required"

    properties = schema.get("properties")
    additional = schema.get("additionalProperties", True)
    if not isinstance(properties, Mapping):
        return True, ""
    for key, prop_schema in properties.items():
        if key not in value:
            continue
        if not isinstance(prop_schema, Mapping):
            continue
        ok, err = _validate_json_schema_value(
            value[key], prop_schema, path=f"{path}.{key}"
        )
        if not ok:
            return False, err
    if additional is False:
        unknown_keys = [key for key in value if key not in properties]
        if unknown_keys:
            return False, f"{path} contains unexpected keys {sorted(unknown_keys)}"
    return True, ""


def _validate_array_schema(
    value: list[Any], schema: Mapping[str, Any], *, path: str
) -> tuple[bool, str]:
    """Validate array-specific schema keys."""
    items = schema.get("items")
    if not isinstance(items, Mapping):
        return True, ""
    for idx, item in enumerate(value):
        ok, err = _validate_json_schema_value(item, items, path=f"{path}[{idx}]")
        if not ok:
            return False, err
    return True, ""


# ---------------------------------------------------------------------------
# MCP runtime helpers using the official Python SDK


class ToolCallingDependencyError(RuntimeError):
    """Raised when the Model Context Protocol SDK is unavailable."""


def _load_sdk() -> tuple[Any, Any, Any, Any]:
    """Import MCP SDK components and return the required classes."""
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

    raise ToolCallingDependencyError(
        "modelcontextprotocol Python SDK not found. Install it with 'pip install mcp'."
    )


try:
    (ClientSession, StdioClientFactory, StdioServerParameters, types_mod) = _load_sdk()
    _SDK_ERROR: Exception | None = None
except ToolCallingDependencyError as exc:  # pragma: no cover
    ClientSession = None  # type: ignore[assignment]
    StdioClientFactory = None  # type: ignore[assignment]
    StdioServerParameters = None  # type: ignore[assignment]
    types_mod = None  # type: ignore[assignment]
    _SDK_ERROR = exc


@dataclass
class ToolCallingManifest:
    """Configuration for a stdio MCP server."""

    name: str
    command: str
    args: list[str]
    env: dict[str, str]

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> ToolCallingManifest:
        """Build a ToolCallingManifest from a mapping."""
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
    def from_json(cls, path: Path) -> ToolCallingManifest:
        """Load a ToolCallingManifest from a JSON file."""
        return cls.from_dict(json.loads(Path(path).read_text()))


class ToolCallingClient:
    """Async MCP client wrapper for stdio servers."""

    def __init__(self, manifest: ToolCallingManifest) -> None:
        """Initialize a ToolCallingClient for a manifest."""
        if _SDK_ERROR is not None:
            raise ToolCallingDependencyError(str(_SDK_ERROR))
        if (
            ClientSession is None
            or StdioClientFactory is None
            or StdioServerParameters is None
        ):
            raise ToolCallingDependencyError("MCP SDK is not available")
        self.manifest = manifest
        self._transport_cm: Any | None = None
        self._session: Any | None = None
        self._read_stream: Any | None = None
        self._write_stream: Any | None = None

    async def __aenter__(self) -> ToolCallingClient:
        """Start the MCP client session and return self."""
        server_params = cast(type[Any], StdioServerParameters)(
            command=self.manifest.command,
            args=self.manifest.args,
            env=self.manifest.env or None,
        )

        transport_factory = cast(type[Any], StdioClientFactory)
        transport_cm = transport_factory(server_params)
        self._transport_cm = transport_cm
        self._read_stream, self._write_stream = await transport_cm.__aenter__()
        session_cls = cast(type[Any], ClientSession)
        self._session = session_cls(self._read_stream, self._write_stream)

        if hasattr(self._session, "__aenter__"):
            await self._session.__aenter__()

        if hasattr(self._session, "initialize"):
            await self._session.initialize()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> bool | None:
        """Close the MCP client session."""
        if self._session is not None:
            if hasattr(self._session, "__aexit__"):
                await self._session.__aexit__(exc_type, exc, tb)
        if self._transport_cm is not None:
            await self._transport_cm.__aexit__(exc_type, exc, tb)
        return None

    async def list_tools(self) -> Any:
        """List tools available from the MCP session."""
        if self._session is None:
            raise RuntimeError("MCP session not started")
        if hasattr(self._session, "list_tools"):
            response = await self._session.list_tools()
            return getattr(response, "tools", response)
        if hasattr(self._session, "tools"):
            return await self._session.tools()
        raise RuntimeError("MCP session missing list_tools")

    async def get_tool(self, tool_name: str) -> Any:
        """Return a tool definition by name."""
        tools = await self.list_tools()
        for tool in tools:
            if tool.name == tool_name:
                return tool
        raise ValueError(f"Tool '{tool_name}' not found")

    async def call_tool(self, tool_name: str, arguments: Mapping[str, Any]) -> Any:
        """Call a tool on the MCP session."""
        if self._session is None:
            raise RuntimeError("MCP session not started")
        return await self._session.call_tool(name=tool_name, arguments=arguments)


def run_sync(coro: Coroutine[Any, Any, _T]) -> _T:
    """Run an async MCP coroutine in a fresh event loop."""
    return asyncio.run(coro)


def list_tools_from_manifest(manifest: ToolCallingManifest) -> Any:
    """List tools from a stdio MCP server defined by the manifest."""
    _toolcalling_limiter.acquire()

    async def _inner() -> Any:
        """Async inner to list tools with a cached client."""
        client = await _get_or_create_client(manifest)
        return await client.list_tools()

    return _POOL.run(_inner())


def call_tool_from_manifest(
    manifest: ToolCallingManifest, tool_name: str, arguments: dict[str, Any]
) -> Any:
    """Invoke a tool on a stdio MCP server defined by the manifest."""
    _toolcalling_limiter.acquire()

    async def _inner() -> Any:
        """Async inner to call a tool with a cached client."""
        client = await _get_or_create_client(manifest)
        return await client.call_tool(tool_name, arguments)

    return _POOL.run(_inner())


def response_to_text(response: object) -> str:
    """Convert MCP tool responses into a user-facing string."""
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


def _format_json_block(data: Mapping[str, Any]) -> str:
    """Format JSON data into a stable, compact string."""
    return json.dumps(data, sort_keys=True)


def system_prompt_from_tool(
    signature: ToolSignature,
    manifest: ToolCallingManifest | None = None,
    additional_instructions: str | None = None,
) -> str:
    """Build a system prompt describing a single MCP tool."""
    parameters = signature.parameters or {}
    parameter_lines = []
    for name, schema in parameters.get("properties", {}).items():
        type_hint = schema.get("type", "any")
        desc = schema.get("description", "")
        parameter_lines.append(f"- {name} ({type_hint}): {desc}")
    parameter_section = (
        "\n".join(parameter_lines) if parameter_lines else "- No structured parameters."
    )

    mcp_header = ""
    if manifest is not None:
        command_line_parts = [manifest.command]
        if manifest.args:
            sanitized_args: list[str] = []
            skip_next = False
            for idx, token in enumerate(manifest.args):
                if skip_next:
                    skip_next = False
                    continue

                lowered = token.lower()
                if lowered in {"--api-key", "--apikey", "--token"}:
                    sanitized_args.append(f"{token} ***")
                    if idx + 1 < len(manifest.args):
                        skip_next = True
                    continue

                if any(keyword in lowered for keyword in ("key", "token", "secret")):
                    sanitized_args.append("***")
                    continue

                sanitized_args.append(token)
            command_line_parts.extend(sanitized_args)

        command_line = " ".join(command_line_parts)

        schema_block = (
            _format_json_block(signature.parameters) if signature.parameters else "{}"
        )

        mcp_header = mcp_prompts.build_mcp_header(
            manifest_name=manifest.name,
            command_line=command_line,
            signature_name=signature.name,
            signature_description=signature.description,
            schema_block=schema_block,
        )

    body = mcp_prompts.build_mcp_body(
        description=signature.description,
        parameter_section=parameter_section,
    )

    sections = [mcp_header, mcp_prompts.TOOL_USE_GUIDELINES, body]
    if additional_instructions:
        sections.append(additional_instructions.strip())
    return "\n\n".join(section for section in sections if section).strip()


def extract_description_from_system(system_prompt: str) -> str | None:
    """Extract a tool description block from a system prompt."""
    if (
        mcp_prompts.PROMPT_TOOL_HEADER not in system_prompt
        or mcp_prompts.PROMPT_TOOL_FOOTER not in system_prompt
    ):
        return None
    start = system_prompt.index(mcp_prompts.PROMPT_TOOL_HEADER) + len(
        mcp_prompts.PROMPT_TOOL_HEADER
    )
    end = system_prompt.index(mcp_prompts.PROMPT_TOOL_FOOTER)
    return system_prompt[start:end].strip()


def load_tool_signature_from_manifest(
    manifest: ToolCallingManifest, tool_name: str
) -> ToolSignature:
    """Load a tool signature from a manifest by tool name."""
    tools = list_tools_from_manifest(manifest)
    tool = next(
        (tool for tool in tools if getattr(tool, "name", None) == tool_name), None
    )
    if tool is None:
        raise ValueError(f"Tool '{tool_name}' not found")
    entry = tool.model_dump(by_alias=True)
    annotations = entry.get("annotations") or {}
    examples = annotations.get("examples")
    output_schema = entry.get("outputSchema") or entry.get("output_schema")
    return ToolSignature.from_tool_entry(
        {
            "type": "function",
            "function": {
                "name": entry.get("name", tool_name),
                "description": entry.get("description", ""),
                "parameters": entry.get("inputSchema", {}),
                "examples": examples,
            },
            "output_schema": output_schema,
        }
    )


def score_query_tool(
    manifest: ToolCallingManifest,
    tool_name: str,
    dataset: Iterable[Mapping[str, Any]],
    description: str,
    argument_key: str = "query",
) -> float:
    """Score how well a tool description matches dataset queries."""
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
        text = response_to_text(response)
        reference = record.get("reference_answer") or record.get(
            "expected_answer_contains", ""
        )
        if reference:
            ratio = SequenceMatcher(
                None,
                " ".join(reference.lower().split()),
                " ".join(text.lower().split()),
            ).ratio()
            if ratio >= 0.6:
                successes += 1
    return successes / total if total else 0.0


def score_url_tool(
    manifest: ToolCallingManifest,
    tool_name: str,
    dataset: Iterable[Mapping[str, Any]],
    description: str,
    argument_key: str = "url",
) -> float:
    """Score how well a tool description matches dataset URLs."""
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
        text = response_to_text(response)
        reference = record.get("reference_answer") or record.get(
            "expected_answer_contains", ""
        )
        if reference:
            ratio = SequenceMatcher(
                None,
                " ".join(reference.lower().split()),
                " ".join(text.lower().split()),
            ).ratio()
            if ratio >= 0.6:
                successes += 1
    return successes / total if total else 0.0

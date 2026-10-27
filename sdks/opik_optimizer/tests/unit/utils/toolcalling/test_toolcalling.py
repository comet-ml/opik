import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling.ops import toolcalling


def test_toolcalling__prepare_tool_optimization_all_tools() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "search docs",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "summarize",
                "description": "summarize output",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]
    prompt = ChatPrompt(system="sys", user="hi", tools=tools)
    resolved, tool_names = toolcalling.prepare_tool_optimization(prompt, True)
    assert resolved.tools is not None
    assert set(tool_names or []) == {"search", "summarize"}


def test_toolcalling__prepare_tool_optimization_subset() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "search docs",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "summarize",
                "description": "summarize output",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]
    prompt = ChatPrompt(system="sys", user="hi", tools=tools)
    resolved, tool_names = toolcalling.prepare_tool_optimization(
        prompt, {"summarize": True, "search": False}
    )
    assert resolved.tools is not None
    assert tool_names == ["summarize"]


def test_toolcalling__prepare_tool_optimization_rejects_empty_dict() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "search docs",
                    "parameters": {"type": "object", "properties": {}},
                },
            }
        ],
    )
    with pytest.raises(ValueError, match="did not enable any tools"):
        toolcalling.prepare_tool_optimization(prompt, {})


def test_toolcalling__prepare_tool_optimization_maps_suffix_name() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "context7.get-library-docs",
                    "description": "docs",
                    "parameters": {"type": "object", "properties": {}},
                },
                "mcp": {
                    "server_label": "context7",
                    "server": {"type": "remote", "url": "https://example.com"},
                    "tool": {"name": "get-library-docs"},
                },
            }
        ],
    )

    _, tool_names = toolcalling.prepare_tool_optimization(
        prompt, {"get-library-docs": True}
    )

    assert tool_names == ["context7.get-library-docs"]


def test_toolcalling__prepare_tool_optimization_rejects_ambiguous_suffix() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "context7.get-library-docs",
                    "description": "docs",
                    "parameters": {"type": "object", "properties": {}},
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "alt.get-library-docs",
                    "description": "alt docs",
                    "parameters": {"type": "object", "properties": {}},
                },
            },
        ],
    )

    with pytest.raises(ValueError, match="Ambiguous optimize_tools entries"):
        toolcalling.prepare_tool_optimization(prompt, {"get-library-docs": True})


def test_toolcalling__build_tool_blocks_redacts_sensitive_metadata() -> None:
    prompt = ChatPrompt(
        system="sys",
        user="hi",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "search docs",
                    "parameters": {"type": "object", "properties": {}},
                },
                "mcp": {
                    "server": {
                        "type": "remote",
                        "url": "https://example.com",
                        "headers": {"X": "secret"},
                    },
                    "auth": {"token": "abc"},
                },
            }
        ],
    )

    blocks = toolcalling.build_tool_blocks_from_prompt(prompt)

    assert "***REDACTED***" in blocks
    assert "https://example.com" not in blocks
    assert "secret" not in blocks


def test_bundle_selector_scopes_duplicate_tool_names_to_one_prompt() -> None:
    from copy import deepcopy

    tools = [
        {
            "type": "function",
            "function": {
                "name": "lookup",
                "description": "Look up",
                "parameters": {"type": "object", "properties": {}},
            },
        }
    ]
    bundle = {
        name: ChatPrompt(name=name, system="Help", tools=deepcopy(tools))
        for name in ["planner", "answerer"]
    }
    resolved, names = toolcalling.resolve_prompt_tools(
        bundle, optimize_tools={"planner.lookup": True}
    )
    assert names == {"planner": ["lookup"]}
    assert isinstance(resolved, dict)
    assert resolved["answerer"].tools == bundle["answerer"].tools
    with pytest.raises(ValueError, match="not found"):
        toolcalling.resolve_prompt_tools(
            bundle, optimize_tools={"missing.lookup": True}
        )


def _two_prompt_tool_bundle() -> dict[str, ChatPrompt]:
    def tool(name: str) -> dict:
        return {
            "type": "function",
            "function": {
                "name": name,
                "description": f"{name} desc",
                "parameters": {"type": "object", "properties": {}},
            },
        }

    # Both prompts keep ChatPrompt's default name; selectors use the bundle keys.
    return {
        "planner": ChatPrompt(system="p", tools=[tool("lookup"), tool("search")]),
        "answerer": ChatPrompt(system="a", tools=[tool("lookup")]),
        "plain": ChatPrompt(system="x"),
    }


@pytest.mark.parametrize(
    "selector",
    [
        {"lookup": True, "planner.lookup": False},
        {"planner.lookup": False, "lookup": True},
    ],
)
def test_bundle_qualified_selectors_override_unqualified_in_any_order(
    selector: dict[str, bool],
) -> None:
    _, names = toolcalling.resolve_prompt_tools(
        _two_prompt_tool_bundle(), optimize_tools=selector
    )
    assert names == {"answerer": ["lookup"]}


def test_bundle_selectors_accept_tool_prefix() -> None:
    _, names = toolcalling.resolve_prompt_tools(
        _two_prompt_tool_bundle(),
        optimize_tools={"tool:lookup": True, "planner.tool:search": True},
    )
    assert names == {"planner": ["lookup", "search"], "answerer": ["lookup"]}


@pytest.mark.parametrize("selector", [{"nope": True}, {"answerer.search": True}])
def test_bundle_selector_errors_list_available_tools(
    selector: dict[str, bool],
) -> None:
    with pytest.raises(ValueError) as error:
        toolcalling.resolve_prompt_tools(
            _two_prompt_tool_bundle(), optimize_tools=selector
        )
    assert "['answerer.lookup', 'planner.lookup', 'planner.search']" in str(error.value)


@pytest.mark.parametrize("selector", [{"planner.lookup": False}, {}, False])
def test_bundle_selector_enabling_nothing_is_not_a_request(
    selector: dict[str, bool] | bool,
) -> None:
    bundle = _two_prompt_tool_bundle()
    resolved, names = toolcalling.resolve_prompt_tools(bundle, optimize_tools=selector)
    assert names is None
    assert isinstance(resolved, dict)
    assert resolved["planner"].tools == bundle["planner"].tools

import pytest
from google.adk import agents as adk_agents
from google.genai import types as genai_types

import opik
from opik.integrations.adk import OpikTracer, track_adk_agent_recursive
from opik.integrations.adk import helpers as opik_adk_helpers
from . import agent_tools
from . import constants, helpers
from .agent_instructions import TOOL_USE_WEATHER
from .constants import (
    APP_NAME,
    USER_ID,
    SESSION_ID,
    MODEL_NAME,
    EXPECTED_USAGE_GOOGLE,
)
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


@pytest.mark.asyncio
async def test_adk__single_agent__multiple_tools__async_happyflow(fake_backend):
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_time_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=TOOL_USE_WEATHER,
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = await helpers.async_build_runner(root_agent)

    events_generator = runner.run_async(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    _ = await helpers.async_extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_time_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_DICT,
        },
        tags=["adk-test"],
        output=ANY_DICT,
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        project_name="adk-test",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=EXPECTED_USAGE_GOOGLE,
                project_name="adk-test",
                source="sdk",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": "The weather in New York is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
                },
                project_name="adk-test",
                source="sdk",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=EXPECTED_USAGE_GOOGLE,
                project_name="adk-test",
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.asyncio
async def test_adk__sequential_agent_with_subagents__every_subagent_has_its_own_span(
    fake_backend,
):
    opik_tracer = OpikTracer()
    root_agent = helpers.root_agent_sequential_with_translator_and_summarizer(
        opik_tracer
    )
    runner = await helpers.async_build_runner(root_agent)

    events_generator = runner.run_async(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    _ = await helpers.async_extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="TextProcessingAssistant",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_DICT,
        },
        output=ANY_DICT,
        input={
            "role": "user",
            "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
        },
        thread_id=SESSION_ID,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="Translator",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name=MODEL_NAME,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        provider=opik_adk_helpers.get_adk_provider(),
                        model=MODEL_NAME,
                        usage=EXPECTED_USAGE_GOOGLE,
                        source="sdk",
                    )
                ],
                source="sdk",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="Summarizer",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name=MODEL_NAME,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        provider=opik_adk_helpers.get_adk_provider(),
                        model=MODEL_NAME,
                        usage=EXPECTED_USAGE_GOOGLE,
                        source="sdk",
                    )
                ],
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@helpers.pytest_skip_for_adk_older_than_1_3_0
@pytest.mark.asyncio
async def test_adk__parallel_agents__appropriate_spans_created_for_subagents(
    fake_backend,
):
    weather_agent = adk_agents.LlmAgent(
        name="weather_agent",
        model=MODEL_NAME,
        instruction="""You are a weather agent. When asked about a city:
        1. ALWAYS call the get_weather tool with the city name
        2. Return the weather information clearly
        3. Start your response with 'WEATHER: ' followed by the weather details""",
        description="Gets the weather info for the city.",
        output_key="weather_info",
        tools=[agent_tools.get_weather],
    )

    timezone_agent = adk_agents.LlmAgent(
        name="timezone_agent",
        model=MODEL_NAME,
        instruction="""You are a time agent. When asked about a city:
        1. ALWAYS call the get_current_time tool with the city name
        2. Return the current time information clearly
        3. Start your response with 'TIME: ' followed by the time details""",
        description="Gets the time info.",
        output_key="time_info",
        tools=[agent_tools.get_current_time],
    )

    parallel_agent = adk_agents.ParallelAgent(
        name="parallel_agent",
        sub_agents=[weather_agent, timezone_agent],
        description="Runs weather and time agents in parallel to get comprehensive city information.",
    )

    # Create a summary agent that will combine the parallel results
    summary_agent = adk_agents.LlmAgent(
        name="summary_agent",
        model=MODEL_NAME,
        instruction="""You are a summarizer agent. You will receive information from parallel agents that have gathered:
        - weather_info: Weather information (starts with 'WEATHER:')
        - time_info: Current time information (starts with 'TIME:')

        Your task is to create a comprehensive response that includes BOTH pieces of information:

        Format your response as:
        "Here's the information for [city]:

        Weather: [weather details]
        Current Time: [time details]"

        IMPORTANT: You must include both weather and time information. Do not omit either piece of information.
        """,
        description="Combines weather and time information from parallel agents into a comprehensive response.",
        output_key="final_summary",
    )

    # Create a sequential agent that first runs parallel agents, then summarizes
    root_agent = adk_agents.SequentialAgent(
        name="main_agent",
        sub_agents=[parallel_agent, summary_agent],
        description="Runs weather and time agents in parallel, then summarizes the results.",
    )

    runner = await helpers.async_build_runner(root_agent)

    project_name = "adk-test-parallel-agents"
    opik_tracer = OpikTracer(project_name=project_name)
    track_adk_agent_recursive(root_agent, opik_tracer)

    events = runner.run_async(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What's the weather and time in New York?")],
        ),
    )

    _ = await helpers.async_extract_final_response_text(events)

    opik.flush_tracker()

    # ADK emits a wrapper span for each sub-agent under parallel_agent. The
    # nominal shape is two LLM spans surrounding one tool span (first call
    # emits a `function_call`, ADK runs the tool, second call turns the
    # function_response into text). The exact sequence depends on the model:
    # Gemini occasionally answers from instruction context without invoking
    # the tool at all, leaving a single LLM span with no tool/second-call. We
    # accept any inner-span sequence here and validate the contents structurally
    # below so the test stays robust against that model-side variability.
    _llm_span = SpanModel(
        id=ANY_BUT_NONE,
        name=MODEL_NAME,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata=ANY_DICT,
        type="llm",
        input=ANY_DICT,
        output=ANY_DICT,
        provider=opik_adk_helpers.get_adk_provider(),
        model=MODEL_NAME,
        usage=EXPECTED_USAGE_GOOGLE,
        project_name=project_name,
        source="sdk",
    )

    def _sub_agent_wrapper(agent_name: str) -> SpanModel:
        return SpanModel(
            id=ANY_BUT_NONE,
            name=agent_name,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            metadata=ANY_DICT,
            type="general",
            input=ANY_DICT,
            output=ANY_DICT,
            project_name=project_name,
            spans=ANY_LIST,
            source="sdk",
        )

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="main_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_DICT,
        },
        output=ANY_DICT,
        input={
            "role": "user",
            "parts": [{"text": "What's the weather and time in New York?"}],
        },
        thread_id=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="parallel_agent",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                project_name=project_name,
                spans=[
                    _sub_agent_wrapper("timezone_agent"),
                    _sub_agent_wrapper("weather_agent"),
                ],
                source="sdk",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="summary_agent",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                project_name=project_name,
                spans=[_llm_span],
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    # parallel sub-agents produce their tool/llm spans in a non-deterministic
    # interleaving order; sort both trees by span name so the comparison
    # stays structural. Sub-agent wrappers expect ``spans=ANY_LIST`` so we
    # skip recursing into matcher sentinels.
    def _sort(node):
        if not isinstance(node.spans, list):
            return
        node.spans = sorted(node.spans, key=lambda s: s.name)
        for s in node.spans:
            _sort(s)

    _sort(EXPECTED_TRACE_TREE)
    _sort(trace_tree)

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)

    # Per-sub-agent structural checks: each wrapper must contain at least one
    # LLM span (the tool call is best-effort because the model may skip it),
    # and any tool span that *was* emitted must point at the right tool with a
    # successful payload.
    parallel_branch = trace_tree.spans[0]
    sub_agent_wrappers = {wrapper.name: wrapper for wrapper in parallel_branch.spans}
    assert set(sub_agent_wrappers.keys()) == {"weather_agent", "timezone_agent"}

    expected_tool_for = {
        "weather_agent": "get_weather",
        "timezone_agent": "get_current_time",
    }
    for sub_name, wrapper in sub_agent_wrappers.items():
        inner_spans = wrapper.spans or []
        llm_spans = [span for span in inner_spans if span.type == "llm"]
        tool_spans = [span for span in inner_spans if span.type == "tool"]

        assert llm_spans, (
            f"{sub_name} produced no LLM span — Opik never observed a model call"
        )
        for llm_span in llm_spans:
            assert llm_span.name == MODEL_NAME
            assert llm_span.model == MODEL_NAME

        for tool_span in tool_spans:
            assert tool_span.name == expected_tool_for[sub_name]
            assert tool_span.input == {"city": "New York"}
            assert tool_span.output == ANY_DICT.containing({"status": "success"})

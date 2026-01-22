import pprint

from google.adk import agents as adk_agents
from google.genai import types as genai_types

import opik
from opik import opik_context
from opik.integrations.adk import OpikTracer
from opik.integrations.adk import helpers as opik_adk_helpers
from .constants import (
    APP_NAME,
    USER_ID,
    SESSION_ID,
    MODEL_NAME,
    EXPECTED_USAGE_KEYS_GOOGLE,
)
from .helpers import build_sync_runner, extract_final_response_text
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)


def test_adk__distributed_headers__sequential_agent_with_subagents__happy_flow(
    fake_backend,
):
    # create root trace/span
    with opik.start_as_current_trace("parent-trace", flush=False):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    opik_tracer = OpikTracer(distributed_headers=distributed_headers)

    translator_to_english = adk_agents.Agent(
        name="Translator",
        model=MODEL_NAME,
        description="Translates text to English.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
    )

    summarizer = adk_agents.Agent(
        name="Summarizer",
        model=MODEL_NAME,
        description="Summarizes text to 1 sentence.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
    )

    root_agent = adk_agents.SequentialAgent(
        name="TextProcessingAssistant",
        sub_agents=[translator_to_english, summarizer],
        description="Runs translator to english then summarizer, in order.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
    )

    runner = build_sync_runner(root_agent)

    INPUT_GERMAN_TEXT = (
        "Wie große Sprachmodelle (LLMs) funktionieren\n\n"
        "Große Sprachmodelle (LLMs) werden mit riesigen Mengen an Text trainiert,\n"
        "um Muster in der Sprache zu erkennen. Sie verwenden eine Art neuronales Netzwerk,\n"
        "das Transformer genannt wird. Dieses ermöglicht es ihnen, den Kontext und die Beziehungen\n"
        "zwischen Wörtern zu verstehen.\n"
        "Wenn man einem LLM eine Eingabe gibt, sagt es die wahrscheinlichsten nächsten Wörter\n"
        "voraus – basierend auf allem, was es während des Trainings gelernt hat.\n"
        "Es „versteht“ nicht im menschlichen Sinne, aber es erzeugt Antworten, die oft intelligent wirken,\n"
        "weil es so viele Daten gesehen hat.\n"
        "Je mehr Daten und Training ein Modell hat, desto besser kann es Aufgaben wie das Beantworten von Fragen,\n"
        "das Schreiben von Texten oder das Zusammenfassen von Inhalten erfüllen.\n"
    )

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="parent-trace",
        project_name="Default Project",
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="parent-span",
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
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
                            "_opik_graph_definition": ANY_BUT_NONE,
                        },
                        output=ANY_DICT.containing(
                            {
                                "content": {
                                    "parts": [{"text": final_response}],
                                    "role": "model",
                                }
                            }
                        ),
                        input={
                            "role": "user",
                            "parts": [{"text": INPUT_GERMAN_TEXT}],
                        },
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
                                        usage=ANY_DICT,
                                    )
                                ],
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
                                        usage=ANY_DICT,
                                    )
                                ],
                            ),
                        ],
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    pprint.pp(trace_tree)

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    translator_span = trace_tree.spans[0].spans[0].spans[0]
    assert_dict_has_keys(translator_span.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)

    summarizer_span = trace_tree.spans[0].spans[0].spans[1]
    assert_dict_has_keys(summarizer_span.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)

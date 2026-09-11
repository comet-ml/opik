import uuid

from ... import llm_constants
from ...testlib import ANY_DICT, ANY

APP_NAME = "ADK_app"
USER_ID = "ADK_test_user"
SESSION_ID = "ADK_" + str(uuid.uuid4())
MODEL_NAME = llm_constants.GEMINI_FLASH

EXPECTED_USAGE_GOOGLE = ANY_DICT.containing(
    {
        "completion_tokens": ANY,
        "original_usage.prompt_token_count": ANY,
        "original_usage.total_token_count": ANY,
        "prompt_tokens": ANY,
        "total_tokens": ANY,
    }
)

# ADK converts LiteLLM usage back into its own format; for OpenAI-backed
# LiteLLM calls we still get the plain OpenAI-style keys plus an
# `original_usage.*` mirror.
EXPECTED_USAGE_ADK_LITELLM_OPENAI = ANY_DICT.containing(
    {
        "prompt_tokens": ANY,
        "completion_tokens": ANY,
        "total_tokens": ANY,
        "original_usage.prompt_tokens": ANY,
        "original_usage.completion_tokens": ANY,
        "original_usage.total_tokens": ANY,
    }
)

# SSE streaming currently loses the `original_usage.*` mirror on ADK's side
# (TODO: add back when ADK supports it).
EXPECTED_USAGE_ADK_LITELLM_OPENAI_STREAMING = ANY_DICT.containing(
    {
        "prompt_tokens": ANY,
        "completion_tokens": ANY,
        "total_tokens": ANY,
    }
)

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

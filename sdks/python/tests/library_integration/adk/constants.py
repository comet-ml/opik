import uuid

APP_NAME = "ADK_app"
USER_ID = "ADK_test_user"
SESSION_ID = "ADK_" + str(uuid.uuid4())
MODEL_NAME = "gemini-2.0-flash"

EXPECTED_USAGE_KEYS_GOOGLE = [
    "completion_tokens",
    "original_usage.candidates_token_count",
    "original_usage.prompt_token_count",
    "original_usage.total_token_count",
    "prompt_tokens",
    "total_tokens",
]

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

from opik.evaluation.metrics.llm_judges.hallucination.template import (
    FewShotExampleHallucination,
    build_messages,
)


_EXAMPLE: FewShotExampleHallucination = {
    "title": "ex1",
    "input": "What is the capital of France?",
    "context": ["France is a country in Europe."],
    "output": "Paris is the capital of France.",
    "score": 0.0,
    "reason": "factual",
}


def _system_content(messages):
    assert messages[0]["role"] == "system"
    return messages[0]["content"]


def test_few_shot_with_context_renders_full_example():
    messages = build_messages(
        input="q",
        output="a",
        context=["c"],
        few_shot_examples=[_EXAMPLE],
    )
    system = _system_content(messages)

    assert "<example>" in system
    assert "Input: What is the capital of France?" in system
    assert "Context: ['France is a country in Europe.']" in system
    assert "Output: Paris is the capital of France." in system
    assert '"score": "0.0"' in system
    assert '"reason": "factual"' in system
    assert "</example>" in system


def test_few_shot_without_context_renders_full_example():
    messages = build_messages(
        input="q",
        output="a",
        context=None,
        few_shot_examples=[_EXAMPLE],
    )
    system = _system_content(messages)

    assert "<example>" in system
    assert "Input: What is the capital of France?" in system
    assert "Output: Paris is the capital of France." in system
    assert '"score": "0.0"' in system
    assert '"reason": "factual"' in system
    assert "</example>" in system


def test_multiple_few_shot_examples_separated():
    second: FewShotExampleHallucination = {
        "title": "ex2",
        "input": "Who wrote Hamlet?",
        "context": ["Shakespeare authored many plays."],
        "output": "Shakespeare wrote Hamlet.",
        "score": 0.0,
        "reason": "correct",
    }

    messages = build_messages(
        input="q",
        output="a",
        context=["c"],
        few_shot_examples=[_EXAMPLE, second],
    )
    system = _system_content(messages)

    assert system.count("<example>") == 2
    assert system.count("</example>") == 2
    assert system.count("EXAMPLES:") == 1
    assert system.index("EXAMPLES:") < system.index("<example>")
    assert "Who wrote Hamlet?" in system
    assert "Shakespeare wrote Hamlet." in system

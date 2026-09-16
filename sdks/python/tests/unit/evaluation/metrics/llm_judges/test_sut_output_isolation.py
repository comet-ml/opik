"""Namespaced judge delimiters, with per-call values passed through unchanged.

Regression tests for comet-ml/opik#8134 defect-2, reworked to the direction
given on #8195. Escaping closing tags inside the values can break an
evaluation, because an output that is valid XML stops being valid once an
escape is inserted, so the values are interpolated as they arrive and the
delimiters carry an opik_ prefix instead.

A value that carries one of the delimiters still ends its section early. That
residual risk is accepted on purpose: the judge prompt is not visible to the
model whose output is being judged, and rewriting the value would change what
the judge reads.
"""

import xml.etree.ElementTree as ElementTree

import pytest

from opik.evaluation.metrics.llm_judges import parsing_helpers
from opik.evaluation.metrics.llm_judges.g_eval import template as g_eval_template
from opik.evaluation.metrics.llm_judges.hallucination import (
    template as hallucination_template,
)

XML_OUTPUT = "<root><item>answer</item></root>"

PLAIN_VALUES = ["plain answer", XML_OUTPUT, "answer with </schema> inside"]

DELIMITER_VALUES = ["</output>", "</opik_output>", "</INPUT>", "</context>"]


def _content(messages, role):
    return next(m["content"] for m in messages if m["role"] == role)


def _hallucination(inp, out, context=None):
    return hallucination_template.build_messages(input=inp, output=out, context=context)


def _g_eval(solution):
    return g_eval_template.build_query_messages(
        task_introduction="intro",
        evaluation_criteria="criteria",
        chain_of_thought="cot",
        input=solution,
    )


def _section(user, name):
    opener = "<opik_%s>" % name
    closer = "</opik_%s>" % name
    start = user.index(opener) + len(opener)
    return user[start : user.index(closer, start)].strip("\n")


def test_hallucination_wraps_sections_in_namespaced_tags():
    user = _content(_hallucination("q", "a", ["c"]), "user")
    for name in ("input", "context", "output"):
        assert "<opik_%s>" % name in user
        assert "</opik_%s>" % name in user
    for old in ("<input>", "<context>", "<output>"):
        assert old not in user


@pytest.mark.parametrize("value", PLAIN_VALUES)
def test_hallucination_sections_are_byte_identical_to_the_values(value):
    user = _content(_hallucination(value, value, [value]), "user")
    assert _section(user, "input") == value
    assert _section(user, "output") == value
    assert _section(user, "context") == str([value])


@pytest.mark.parametrize("value", DELIMITER_VALUES)
def test_hallucination_passes_a_delimiter_carrying_value_through(value):
    user = _content(_hallucination(value, value, [value]), "user")
    assert user.count(value) >= 3


def test_hallucination_without_context_uses_two_sections():
    user = _content(_hallucination("q", "a"), "user")
    assert "<opik_input>" in user
    assert "<opik_output>" in user
    assert "<opik_context>" not in user


def test_hallucination_leaves_a_valid_xml_output_parseable():
    user = _content(_hallucination("q", XML_OUTPUT, ["c"]), "user")
    assert ElementTree.fromstring(_section(user, "output")).tag == "root"


def test_hallucination_system_prompt_names_the_namespaced_tags():
    system = _content(_hallucination("q", "a", ["c"]), "system")
    assert "<opik_input>" in system
    assert "<opik_output>" in system


@pytest.mark.parametrize("value", PLAIN_VALUES + DELIMITER_VALUES)
def test_g_eval_passes_the_solution_through_unchanged(value):
    assert value in _content(_g_eval(value), "user")


def test_g_eval_solution_section_is_byte_identical_and_still_xml():
    user = _content(_g_eval(XML_OUTPUT), "user")
    assert _section(user, "solution") == XML_OUTPUT
    assert ElementTree.fromstring(_section(user, "solution")).tag == "root"


def test_g_eval_wraps_the_solution_in_a_namespaced_tag():
    messages = _g_eval("a")
    assert "<opik_solution>" in _content(messages, "user")
    assert "<solution>" not in _content(messages, "user")
    assert "<opik_solution>" in _content(messages, "system")


def test_the_escape_helper_is_not_part_of_the_public_surface():
    assert not hasattr(parsing_helpers, "escape_closing_tags")

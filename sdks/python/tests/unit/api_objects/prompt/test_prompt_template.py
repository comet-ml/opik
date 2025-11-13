import pytest
from opik.api_objects.prompt import prompt_template
from opik.api_objects.prompt.types import PromptType
from opik import exceptions


def test_prompt__format__happyflow():
    PROMPT_TEMPLATE = "Hi, my name is {{name}}, I live in {{city}}."

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE)

    result = tested.format(name="Harry", city="London")
    assert result == "Hi, my name is Harry, I live in London."


def test_prompt__format__one_placeholder_used_multiple_times():
    PROMPT_TEMPLATE = (
        "Hi, my name is {{name}}, I live in {{city}}. I repeat, my name is {{name}}"
    )

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE)

    result = tested.format(name="Harry", city="London")
    assert (
        result == "Hi, my name is Harry, I live in London. I repeat, my name is Harry"
    )


def test_prompt__format__passed_arguments_that_are_not_in_template__error_raised_with_correct_report_info():
    PROMPT_TEMPLATE = "Hi, my name is {{name}}, I live in {{city}}."

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE)

    with pytest.raises(
        exceptions.PromptPlaceholdersDontMatchFormatArguments
    ) as exc_info:
        tested.format(name="Harry", city="London", nemesis_name="Voldemort")

    assert exc_info.value.format_arguments == set(["name", "city", "nemesis_name"])
    assert exc_info.value.prompt_placeholders == set(
        [
            "name",
            "city",
        ]
    )
    assert exc_info.value.symmetric_difference == set(["nemesis_name"])


def test_prompt__format__some_placeholders_dont_have_corresponding_format_arguments__error_raised_with_correct_report_info():
    PROMPT_TEMPLATE = "Hi, my name is {{name}}, I live in {{city}}."

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE)

    with pytest.raises(
        exceptions.PromptPlaceholdersDontMatchFormatArguments
    ) as exc_info:
        tested.format(name="Harry")

    assert exc_info.value.format_arguments == set(["name"])
    assert exc_info.value.prompt_placeholders == set(["name", "city"])
    assert exc_info.value.symmetric_difference == set(["city"])


def test_prompt__format__some_placeholders_dont_have_corresponding_format_arguments_AND_there_are_format_arguments_that_are_not_in_the_template__error_raised_with_correct_report_info():
    PROMPT_TEMPLATE = "Hi, my name is {{name}}, I live in {{city}}."

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE)

    with pytest.raises(
        exceptions.PromptPlaceholdersDontMatchFormatArguments
    ) as exc_info:
        tested.format(name="Harry", nemesis_name="Voldemort")

    assert exc_info.value.format_arguments == set(["name", "nemesis_name"])
    assert exc_info.value.prompt_placeholders == set(["name", "city"])
    assert exc_info.value.symmetric_difference == set(["city", "nemesis_name"])


def test_prompt__format_jinja2__happyflow():
    PROMPT_TEMPLATE = "Hi, my name is {{ name }}, I live in {{ city }}."

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE, type=PromptType.JINJA2)

    result = tested.format(name="Harry", city="London")
    assert result == "Hi, my name is Harry, I live in London."


def test_prompt__format_jinja2__with_control_flow():
    PROMPT_TEMPLATE = """
    {% if is_wizard %}
    {{ name }} is a wizard who lives in {{ city }}.
    {% else %}
    {{ name }} is a muggle who lives in {{ city }}.
    {% endif %}
    """

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE, type=PromptType.JINJA2)

    wizard_result = tested.format(name="Harry", city="London", is_wizard=True)
    assert "Harry is a wizard who lives in London." in wizard_result.strip()

    muggle_result = tested.format(name="Dudley", city="Surrey", is_wizard=False)
    assert "Dudley is a muggle who lives in Surrey." in muggle_result.strip()


def test_prompt__format_jinja2__with_loops():
    PROMPT_TEMPLATE = """
    {{ name }}'s friends are:
    {% for friend in friends %}
    - {{ friend }}
    {% endfor %}
    """

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE, type=PromptType.JINJA2)

    result = tested.format(name="Harry", friends=["Ron", "Hermione", "Neville"])

    assert "Harry's friends are:" in result
    assert "- Ron" in result
    assert "- Hermione" in result
    assert "- Neville" in result


def test_prompt__format_jinja2__with_filters():
    PROMPT_TEMPLATE = "{{ name | upper }} lives in {{ city | lower }}."

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE, type=PromptType.JINJA2)

    result = tested.format(name="Harry", city="LONDON")
    assert result == "HARRY lives in london."


def test_prompt__format__none_values_render_as_empty_strings() -> None:
    PROMPT_TEMPLATE = "Primary: {{primary}} Secondary: {{secondary}}"

    tested = prompt_template.PromptTemplate(PROMPT_TEMPLATE)

    result = tested.format(primary="cat", secondary=None)
    assert result == "Primary: cat Secondary: "

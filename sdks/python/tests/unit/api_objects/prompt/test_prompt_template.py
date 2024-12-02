import pytest
from opik.api_objects.prompt import prompt_template
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

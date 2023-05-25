from comet_llm import convert


def test_call_data_to_dict__happyflow():
    result = convert.call_data_to_dict(
        id="the-id",
        prompt="the-prompt",
        outputs="the-outputs",
        name="the-name",
        metadata="the-metadata",
        prompt_template="prompt-template",
        prompt_template_variables="prompt-template-variables",
        category="the-category",
        start_timestamp="start-timestamp",
        end_timestamp="end-timestamp",
        duration="the-duration"
    )

    assert result == {
        "id": "the-id",
        "category": "the-category",
        "name": "the-name",
        "inputs": {
            "final_prompt": "the-prompt",
            "prompt_template": "prompt-template",
            "prompt_template_variables": "prompt-template-variables"
        },
        "outputs": {"output": "the-outputs"},
        "duration": "the-duration",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "metadata": "the-metadata",
        "context": []
    }


def test_node_data_to_dict__input_and_output_are_dicts__both_inserted_to_result_dict():
    result = convert.node_data_to_dict(
        id="the-id",
        inputs={"input-key": "input-value"},
        outputs={"output-key": "output-value"},
        metadata="the-metadata",
        category="the-category",
        start_timestamp="start-timestamp",
        end_timestamp="end-timestamp",
        duration="the-duration"
    )

    assert result == {
        "id": "the-id",
        "category": "the-category",
        "inputs": {"input-key": "input-value"},
        "outputs": {"output-key": "output-value"},
        "duration": "the-duration",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "metadata": "the-metadata",
        "context": []
    }


def test_node_data_to_dict__input_and_output_are_not_dicts__both_turned_to_dicts_and_inserted_to_result_dict():
    result = convert.node_data_to_dict(
        id="the-id",
        inputs="the-inputs",
        outputs="the-outputs",
        metadata="the-metadata",
        category="the-category",
        start_timestamp="start-timestamp",
        end_timestamp="end-timestamp",
        duration="the-duration"
    )

    assert result == {
        "id": "the-id",
        "category": "the-category",
        "inputs": {"input": "the-inputs"},
        "outputs": {"output": "the-outputs"},
        "duration": "the-duration",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "metadata": "the-metadata",
        "context": []
    }
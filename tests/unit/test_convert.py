from comet_llm import convert


def test_call_data_to_dict__happyflow():
    result = convert.call_data_to_dict(
        id="the-id",
        prompt="the-prompt",
        outputs="the-outputs",
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


def test_node_data_to_dict__happyflow():
    assert 0
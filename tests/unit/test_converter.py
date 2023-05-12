from comet_llm import converter


def test_call_data_to_dict():
    result = converter.call_data_to_dict(
        id="the-id",
        prompt="the-prompt",
        outputs="the-outputs",
        metadata="the-metadata",
        prompt_template="prompt-template",
        prompt_variables="prompt-variables",
        start_timestamp="start-timestamp",
        end_timestamp="end-timestamp",
        duration="the-duration"
    )

    assert result == {
        "_id": "the-id",
        "_type": "llm_call",
        "inputs": {
            "final_prompt": "the-prompt",
            "prompt_template": "prompt-template",
            "prompt_variables": "prompt-variables"
        },
        "outputs": "the-outputs",
        "duration": "the-duration",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "metadata": "the-metadata",
        "context": []
    }

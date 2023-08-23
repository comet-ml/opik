from comet_llm.prompts import convert

def test_call_data_to_dict__happyflow():
    result = convert.call_data_to_dict(
        prompt="the-prompt",
        outputs="the-outputs",
        metadata="the-metadata",
        prompt_template="prompt-template",
        prompt_template_variables="prompt-template-variables",
        start_timestamp="start-timestamp",
        end_timestamp="end-timestamp",
        duration="the-duration"
    )

    assert result == {
        "id": 1,
        "category": "llm-call",
        "name": "llm-call-1",
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
        "parent_ids": []
    }

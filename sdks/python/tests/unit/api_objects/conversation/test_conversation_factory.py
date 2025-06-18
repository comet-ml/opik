import datetime

import pytest

from opik.rest_api import TracePublic

from opik.api_objects.conversation import conversation_factory


@pytest.mark.parametrize(
    "traces,expected_discussion",
    [
        (
            [
                TracePublic(
                    input={"x": "test input 997"},
                    output={"output": "test output"},
                    start_time=datetime.datetime.now(),
                )
            ],
            [
                {"role": "user", "content": "test input 997"},
                {"role": "assistant", "content": "test output"},
            ],
        )
    ],
)
def test_create_conversation_from_traces(traces, expected_discussion):
    def input_transform(input):
        return input["x"]

    def output_transform(output):
        return output["output"]

    discussion = conversation_factory.create_conversation_from_traces(
        traces, input_transform, output_transform
    )
    assert discussion.as_json_list() == expected_discussion

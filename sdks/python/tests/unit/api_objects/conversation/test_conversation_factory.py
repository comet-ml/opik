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
        ),
        (  # test that traces are sorted by time - the first trace should be first
            [
                TracePublic(
                    input={"x": "test input 3"},
                    output={"output": "test output"},
                    start_time=datetime.datetime.now(),
                ),
                TracePublic(
                    input={"x": "test input 1"},
                    output={"output": "test output"},
                    start_time=datetime.datetime.now() - datetime.timedelta(seconds=10),
                ),
                TracePublic(
                    input={"x": "test input 2"},
                    output={"output": "test output"},
                    start_time=datetime.datetime.now() - datetime.timedelta(seconds=5),
                ),
            ],
            [
                {"role": "user", "content": "test input 1"},
                {"role": "assistant", "content": "test output"},
                {"role": "user", "content": "test input 2"},
                {"role": "assistant", "content": "test output"},
                {"role": "user", "content": "test input 3"},
                {"role": "assistant", "content": "test output"},
            ],
        ),
        (  # test that trace's input or output are filtered out if it isn't in the expected format
            [
                TracePublic(
                    input={"y": "test input 1"},  # wrong input
                    output={"output": "test output"},
                    start_time=datetime.datetime.now(),
                ),
                TracePublic(
                    input={"x": "test input 2"},
                    output={"result": "test output"},  # wrong output
                    start_time=datetime.datetime.now(),
                ),
            ],
            [
                {"role": "assistant", "content": "test output"},
                {"role": "user", "content": "test input 2"},
            ],
        ),
    ],
)
def test_create_conversation_from_traces(traces, expected_discussion):
    def input_transform(input):
        if "x" not in input:
            return None
        return input["x"]

    def output_transform(output):
        if "output" not in output:
            return None
        return output["output"]

    discussion = conversation_factory.create_conversation_from_traces(
        traces, input_transform, output_transform
    )
    assert discussion.as_json_list() == expected_discussion

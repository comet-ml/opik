import datetime
from unittest import mock

from opik.api_objects.conversation import conversation_thread
from opik.api_objects.threads import threads_client
from opik.evaluation.threads import evaluation_result, helpers
from opik.evaluation.metrics import score_result
from opik.rest_api import TracePublic, TraceThread


def test_log_feedback_scores():
    """Test that log_feedback_scores correctly logs feedback scores."""
    # Create mock results
    score1 = score_result.ScoreResult(name="metric1", value=0.8, reason="Good")
    score2 = score_result.ScoreResult(name="metric2", value=0.6, reason="Average")
    score3 = score_result.ScoreResult(
        name="metric3", value=0.0, reason="Failed", scoring_failed=True
    )
    results = [
        evaluation_result.ThreadEvaluationResult(
            thread_id="thread_1", scores=[score1, score2, score3]
        )
    ]

    expected_scores = [score1, score2]

    mock_client = mock.MagicMock(spec=threads_client.ThreadsClient)
    project_name = "test_project"

    # Call the method
    helpers.log_feedback_scores(results, project_name=project_name, client=mock_client)

    # Verify the log_threads_feedback_scores call
    mock_client.log_threads_feedback_scores.assert_called_once()
    call_args = mock_client.log_threads_feedback_scores.call_args[1]
    assert call_args["project_name"] == project_name

    # Check the scores
    scores = call_args["scores"]
    assert len(scores) == 2
    for i, score in enumerate(scores):
        assert score["id"] == "thread_1"
        assert score["name"] == expected_scores[i].name
        assert score["value"] == expected_scores[i].value
        assert score["reason"] == expected_scores[i].reason


def test_load_conversation_thread():
    """Test that load_conversation_thread correctly fetches and transforms traces."""
    # Setup mock traces
    mock_traces = [
        TracePublic(
            id="trace_1",
            input={"content": "user input"},
            output={"content": "assistant output"},
            start_time=datetime.datetime.now(),
        ),
        TracePublic(
            id="trace_2",
            input={"content": "user input 2"},
            output={"content": "assistant output 2"},
            start_time=datetime.datetime.now(),
        ),
    ]
    mock_opik_client = mock.MagicMock()
    mock_opik_client.search_traces.return_value = mock_traces

    # Mock transform functions
    def input_transform(json_input):
        return json_input.get("content", "")

    def output_transform(json_output):
        return json_output.get("content", "")

    project_name = "test_project"

    # Call the method
    thread = TraceThread(id="thread_1")
    result = helpers.load_conversation_thread(
        thread=thread,
        trace_input_transform=input_transform,
        trace_output_transform=output_transform,
        max_results=10,
        project_name=project_name,
        client=mock_opik_client,
    )

    # Verify the result
    assert isinstance(result, conversation_thread.ConversationThread) is True
    assert len(result.discussion) == 4  # 2 user messages + 2 assistant messages

    assert result.discussion[0].role == "user"
    assert result.discussion[0].content == "user input"
    assert result.discussion[1].role == "assistant"
    assert result.discussion[1].content == "assistant output"

    # Verify the search_traces call
    mock_opik_client.search_traces.assert_called_once_with(
        project_name=project_name,
        filter_string=f'thread_id = "{thread.id}"',
        max_results=10,
    )

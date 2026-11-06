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


def test_log_feedback_scores__thread_without_scores__not_logged():
    """Skipped threads and threads whose metrics all failed are not logged."""
    failed_score = score_result.ScoreResult(
        name="metric1", value=0.0, reason="Failed", scoring_failed=True
    )
    results = [
        evaluation_result.ThreadEvaluationResult(thread_id="skipped", scores=[]),
        evaluation_result.ThreadEvaluationResult(
            thread_id="all_failed", scores=[failed_score]
        ),
    ]

    mock_client = mock.MagicMock(spec=threads_client.ThreadsClient)

    helpers.log_feedback_scores(
        results, project_name="test_project", client=mock_client
    )

    mock_client.log_threads_feedback_scores.assert_not_called()


def test_log_feedback_scores__mixed_threads__only_threads_with_scores_logged():
    score = score_result.ScoreResult(name="metric1", value=0.8, reason="Good")
    results = [
        evaluation_result.ThreadEvaluationResult(thread_id="skipped", scores=[]),
        evaluation_result.ThreadEvaluationResult(thread_id="scored", scores=[score]),
    ]

    mock_client = mock.MagicMock(spec=threads_client.ThreadsClient)

    helpers.log_feedback_scores(
        results, project_name="test_project", client=mock_client
    )

    mock_client.log_threads_feedback_scores.assert_called_once()
    scores = mock_client.log_threads_feedback_scores.call_args[1]["scores"]
    assert [s["id"] for s in scores] == ["scored"]


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
        truncate=False,
    )


def test_load_conversation_thread__thread_id_with_double_quote__filter_stays_scoped():
    """A thread id containing a double quote must not break out of the filter.

    The filter is built by string interpolation, so an unescaped quote would
    terminate the literal early and make the filter match other threads'
    traces. The OQL parser reads a doubled quote inside a double-quoted
    literal as one quote character.
    """
    mock_opik_client = mock.MagicMock()
    mock_opik_client.search_traces.return_value = []

    thread = TraceThread(id='thread"123')
    helpers.load_conversation_thread(
        thread=thread,
        trace_input_transform=lambda x: x.get("content", ""),
        trace_output_transform=lambda x: x.get("content", ""),
        max_results=10,
        project_name="test_project",
        client=mock_opik_client,
    )

    call_kwargs = mock_opik_client.search_traces.call_args[1]
    assert call_kwargs["filter_string"] == 'thread_id = "thread""123"'

    # The generated filter must parse back to the original thread id.
    from opik.api_objects import opik_query_language

    parsed = opik_query_language.OpikQueryLanguage.for_traces(
        call_kwargs["filter_string"]
    ).get_filter_expressions()
    assert parsed[0]["value"] == 'thread"123'


def _load_thread_with_traces(trace_count, max_results, count_known=True):
    start = datetime.datetime(2026, 1, 1)
    mock_opik_client = mock.MagicMock()
    # Newest first and cut at max_results, like the backend.
    mock_opik_client.search_traces.return_value = [
        TracePublic(
            id=f"trace_{i}",
            input={"content": f"user input {i}"},
            output={"content": f"assistant output {i}"},
            start_time=start + datetime.timedelta(minutes=i),
        )
        for i in reversed(range(trace_count))
    ][:max_results]
    return helpers.load_conversation_thread(
        thread=TraceThread(
            id="thread_1",
            # The backend counts two messages per trace.
            number_of_messages=trace_count * 2 if count_known else None,
        ),
        trace_input_transform=lambda x: x.get("content", ""),
        trace_output_transform=lambda x: x.get("content", ""),
        max_results=max_results,
        project_name="test_project",
        client=mock_opik_client,
    )


def _warnings(caplog):
    return [r.getMessage() for r in caplog.records if r.levelname == "WARNING"]


def test_load_conversation_thread__more_traces_than_max_results__warning_logged(
    caplog,
):
    with caplog.at_level("WARNING", logger="opik.evaluation.threads.helpers"):
        result = _load_thread_with_traces(trace_count=3, max_results=2)

    assert [item.content for item in result.discussion] == [
        "user input 1",
        "assistant output 1",
        "user input 2",
        "assistant output 2",
    ]
    warnings = _warnings(caplog)
    assert len(warnings) == 1
    assert "Thread 'thread_1' has 3 traces" in warnings[0]
    assert "max_traces_per_thread=2" in warnings[0]
    assert "Only its 2 most recent traces" in warnings[0]


def test_load_conversation_thread__trace_count_equals_max_results__no_warning(
    caplog,
):
    with caplog.at_level("WARNING", logger="opik.evaluation.threads.helpers"):
        result = _load_thread_with_traces(trace_count=2, max_results=2)

    assert len(result.discussion) == 4
    assert _warnings(caplog) == []


def test_load_conversation_thread__unknown_trace_count_at_max_results__warning_logged(
    caplog,
):
    with caplog.at_level("WARNING", logger="opik.evaluation.threads.helpers"):
        _load_thread_with_traces(trace_count=2, max_results=2, count_known=False)

    warnings = _warnings(caplog)
    assert len(warnings) == 1
    assert "Thread 'thread_1' has at least 2 traces" in warnings[0]


def test_load_conversation_thread__fewer_traces_than_max_results__no_warning(caplog):
    with caplog.at_level("WARNING", logger="opik.evaluation.threads.helpers"):
        result = _load_thread_with_traces(trace_count=1, max_results=2)

    assert len(result.discussion) == 2
    assert _warnings(caplog) == []

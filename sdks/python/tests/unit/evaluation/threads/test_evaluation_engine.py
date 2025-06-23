import datetime
import unittest
from unittest import mock
from unittest.mock import patch

import pytest

from opik import exceptions
from opik.api_objects.conversation import conversation_thread
from opik.api_objects.threads import threads_client
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.conversation import conversation_thread_metric
from opik.evaluation.threads import evaluation_engine, evaluation_result
from opik.rest_api import TraceThread, TracePublic


class TestThreadsEvaluationEngine(unittest.TestCase):
    def setUp(self):
        # Mock the threads client
        self.mock_client = mock.MagicMock(spec=threads_client.ThreadsClient)
        self.mock_opik_client = mock.MagicMock()
        self.mock_client.opik_client = self.mock_opik_client

        # Setup the evaluation engine
        self.project_name = "test_project"
        self.engine = evaluation_engine.ThreadsEvaluationEngine(
            client=self.mock_client,
            project_name=self.project_name,
            number_of_workers=2,
            verbose=0,
        )

        # Mock trace and span for testing
        self.mock_trace = mock.MagicMock()
        self.mock_trace.id = "trace_id_123"
        self.mock_span = mock.MagicMock()
        self.mock_span.id = "span_id_456"
        self.mock_opik_client.trace.return_value = self.mock_trace
        self.mock_opik_client.span.return_value = self.mock_span

    def test__get_conversation_thread(self):
        """Test that _get_conversation_tread correctly fetches and transforms traces."""
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
        self.mock_opik_client.search_traces.return_value = mock_traces

        # Mock transform functions
        def input_transform(json_input):
            return json_input.get("content", "")

        def output_transform(json_output):
            return json_output.get("content", "")

        # Call the method
        thread = TraceThread(id="thread_1")
        result = self.engine._get_conversation_thread(
            thread=thread,
            trace_input_transform=input_transform,
            trace_output_transform=output_transform,
            max_results=10,
        )

        # Verify the result
        self.assertIsInstance(result, conversation_thread.ConversationThread)
        self.assertEqual(
            len(result.discussion), 4
        )  # 2 user messages + 2 assistant messages
        self.assertEqual(result.discussion[0].role, "user")
        self.assertEqual(result.discussion[0].content, "user input")
        self.assertEqual(result.discussion[1].role, "assistant")
        self.assertEqual(result.discussion[1].content, "assistant output")

        # Verify the search_traces call
        self.mock_opik_client.search_traces.assert_called_once_with(
            project_name=self.project_name,
            filter_string=f"thread_id = {thread.id}",
            max_results=10,
        )

    def test__log_feedback_scores(self):
        """Test that _log_feedback_scores correctly logs feedback scores."""
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

        # Call the method
        self.engine._log_feedback_scores(results)

        # Verify the log_threads_feedback_scores call
        self.mock_client.log_threads_feedback_scores.assert_called_once()
        call_args = self.mock_client.log_threads_feedback_scores.call_args[1]
        self.assertEqual(call_args["project_name"], self.project_name)

        # Check the scores
        scores = call_args["scores"]
        self.assertEqual(len(scores), 2)
        for i, score in enumerate(scores):
            self.assertEqual(score["id"], "thread_1")
            self.assertEqual(score["name"], expected_scores[i].name)
            self.assertEqual(score["value"], expected_scores[i].value)
            self.assertEqual(score["reason"], expected_scores[i].reason)

    def test_evaluate_threads__happy_path(self):
        """Test the full evaluate_threads method with mocked dependencies."""
        # Mock threads
        mock_threads = [
            TraceThread(id="thread_1"),
            TraceThread(id="thread_2"),
        ]
        self.mock_client.search_threads.return_value = mock_threads

        # Mock evaluate_thread to return test results
        def mock_evaluate_thread(*args, **kwargs):
            thread = kwargs.get("thread")
            return evaluation_result.ThreadEvaluationResult(
                thread_id=thread.id,
                scores=[
                    score_result.ScoreResult(name="metric1", value=0.8, reason="Good"),
                    score_result.ScoreResult(
                        name="metric2", value=0.6, reason="Average"
                    ),
                ],
            )

        # Create mock metrics
        mock_metric1 = mock.MagicMock(
            spec=conversation_thread_metric.ConversationThreadMetric
        )
        mock_metric1.name = "metric1"
        mock_metric2 = mock.MagicMock(
            spec=conversation_thread_metric.ConversationThreadMetric
        )
        mock_metric2.name = "metric2"
        metrics = [mock_metric1, mock_metric2]

        # Patch the _evaluate_thread method
        self.engine.evaluate_thread = mock_evaluate_thread

        # Call the method
        result = self.engine.evaluate_threads(
            filter_string="test_filter",
            eval_project_name="eval_project",
            metrics=metrics,
            trace_input_transform=lambda x: "",
            trace_output_transform=lambda x: "",
            max_traces_per_thread=10,
        )

        # Verify the result
        self.assertEqual(len(result.results), 2)
        for result in result.results:
            self.assertTrue(result.thread_id in ["thread_1", "thread_2"])
            self.assertEqual(len(result.scores), 2)

        # Verify the log_feedback_scores was called
        self.mock_client.log_threads_feedback_scores.assert_called()

    def test_evaluate_threads__with_empty_traces__warning_logged(self):
        """Test evaluate_threads when no traces are found."""
        # Mock an empty traces list
        self.mock_opik_client.search_traces.return_value = []

        # Mock threads
        mock_threads = [
            TraceThread(id="thread_1"),
        ]
        self.mock_client.search_threads.return_value = mock_threads

        # Create mock metrics
        mock_metric1 = mock.MagicMock(
            spec=conversation_thread_metric.ConversationThreadMetric
        )
        mock_metric1.name = "metric1"
        mock_score1 = score_result.ScoreResult(name="metric1", value=0.8, reason="Good")
        mock_metric1.score.return_value = mock_score1

        # Call the method
        with self.assertLogs(
            level="WARNING", logger="opik.evaluation.threads.evaluation_engine"
        ) as log_context:
            results = self.engine.evaluate_threads(
                filter_string="filter_string",
                eval_project_name="eval_project",
                metrics=[mock_metric1],
                trace_input_transform=lambda x: "",
                trace_output_transform=lambda x: "",
                max_traces_per_thread=10,
            )

        # Verify the result
        self.assertEqual(len(results.results), 1)
        result = results.results[0]

        self.assertEqual(result.thread_id, "thread_1")
        self.assertEqual(len(result.scores), 0)

        # Verify error was logged
        self.assertTrue(
            any(
                f"Thread '{mock_threads[0].id}' has no conversation traces. Skipping evaluation."
                in message
                for message in log_context.output
            )
        )

    def test_evaluate_threads__no_threads_found__exception_raised(self):
        """Test evaluate_threads when no threads are found."""
        # Mock an empty threads list
        self.mock_client.search_threads.return_value = []

        # Create mock metrics
        mock_metric = mock.MagicMock(
            spec=conversation_thread_metric.ConversationThreadMetric
        )
        metrics = [mock_metric]

        # Call the method
        with pytest.raises(exceptions.EvaluationError):
            self.engine.evaluate_threads(
                filter_string="test_filter",
                eval_project_name="eval_project",
                metrics=metrics,
                trace_input_transform=lambda x: "",
                trace_output_transform=lambda x: "",
            )

    def test_evaluate_threads__with_multiple_trace_threads__executor_called_with_correct_args(
        self,
    ):
        """Test evaluate_threads with multiple trace threads and multiple workers.
        Verify the executor was called with the right number of workers and tasks."""

        # Create an engine with multiple workers
        engine = evaluation_engine.ThreadsEvaluationEngine(
            client=self.mock_client,
            project_name=self.project_name,
            number_of_workers=4,  # Use multiple workers
            verbose=1,
        )

        # Mock threads - create several to test concurrency
        mock_threads = [
            TraceThread(id=f"thread_{i}")
            for i in range(10)  # Create 10 threads
        ]
        self.mock_client.search_threads.return_value = mock_threads

        # Mock the evaluation executor
        with mock.patch(
            "opik.evaluation.threads.evaluation_engine.evaluation_tasks_executor.execute"
        ) as mock_execute:
            # Create a mock result
            results = [
                evaluation_result.ThreadEvaluationResult(
                    thread_id=f"thread_{i}",
                    scores=[
                        score_result.ScoreResult(
                            name="metric", value=0.8, reason="Good"
                        ),
                    ],
                )
                for i in range(10)
            ]
            mock_execute.return_value = results

            # Create mock metric
            mock_metric = mock.MagicMock(
                spec=conversation_thread_metric.ConversationThreadMetric
            )
            metrics = [mock_metric]

            # Call the method
            engine.evaluate_threads(
                filter_string=None,
                eval_project_name="eval_project",
                metrics=metrics,
                trace_input_transform=lambda x: "",
                trace_output_transform=lambda x: "",
            )

            # Verify the number of tasks matches the number of threads
            self.assertEqual(len(mock_execute.call_args[0][0]), 10)

            # Verify the executor was called with the right number of workers and verbose value
            mock_execute.assert_called_once()
            self.assertEqual(mock_execute.call_args[1]["workers"], 4)
            self.assertEqual(mock_execute.call_args[1]["verbose"], 1)

    def test_evaluate_threads__with_no_metrics__raises_exception(self):
        """Test _evaluate_thread when no metrics are provided."""
        with pytest.raises(ValueError):
            # Call the method with an empty metrics list
            self.engine.evaluate_threads(
                filter_string="filter_string",
                eval_project_name="eval_project",
                metrics=[],  # Empty metrics list
                trace_input_transform=lambda x: "",
                trace_output_transform=lambda x: "",
                max_traces_per_thread=10,
            )

    @patch("opik.decorator.base_track_decorator.opik_client")
    def test_evaluate_thread(self, decorator_opik_client):
        """Test that evaluate_thread correctly evaluates a thread with metrics."""
        mocked_opik_client = mock.MagicMock()
        decorator_opik_client.get_client_cached.return_value = mocked_opik_client

        # Create a mock conversation thread
        mock_conversation = conversation_thread.ConversationThread()
        mock_conversation.add_user_message("Hello")
        mock_conversation.add_assistant_message("Hi there")

        # Mock the _get_conversation_thread method
        with mock.patch.object(
            self.engine, "_get_conversation_thread", return_value=mock_conversation
        ):
            # Create mock metrics
            mock_metric1 = mock.MagicMock(
                spec=conversation_thread_metric.ConversationThreadMetric
            )
            mock_metric1.name = "metric1"
            mock_score1 = score_result.ScoreResult(
                name="metric1", value=0.8, reason="Good"
            )
            mock_metric1.score.return_value = mock_score1

            mock_metric2 = mock.MagicMock(
                spec=conversation_thread_metric.ConversationThreadMetric
            )
            mock_metric2.name = "metric2"
            mock_score2 = score_result.ScoreResult(
                name="metric2", value=0.6, reason="Average"
            )
            mock_metric2.score.return_value = [mock_score2]  # Test list return

            metrics = [mock_metric1, mock_metric2]

            # Call the method
            result = self.engine.evaluate_thread(
                thread=TraceThread(id="thread_1"),
                eval_project_name="eval_project",
                metrics=metrics,
                trace_input_transform=lambda x: "",
                trace_output_transform=lambda x: "",
                max_traces_per_thread=10,
            )

            # Verify the result
            self.assertEqual(result.thread_id, "thread_1")
            self.assertEqual(len(result.scores), 2)
            self.assertEqual(result.scores[0].name, "metric1")
            self.assertEqual(result.scores[0].value, 0.8)
            self.assertEqual(result.scores[1].name, "metric2")
            self.assertEqual(result.scores[1].value, 0.6)

            # Verify the trace and span calls
            self.mock_opik_client.trace.assert_called()
            mocked_opik_client.span.assert_called()

            # Verify metrics were called with the right parameters
            conversation_list = mock_conversation.model_dump()["discussion"]
            mock_metric1.score.assert_called_once_with(conversation_list)
            mock_metric2.score.assert_called_once_with(conversation_list)

    @patch("opik.decorator.base_track_decorator.opik_client")
    def test_evaluate_thread__error_in_metric_logged(self, decorator_opik_client):
        """Test that evaluate_thread logs errors in metrics."""
        mocked_opik_client = mock.MagicMock()
        decorator_opik_client.get_client_cached.return_value = mocked_opik_client

        # Create a mock thread
        thread = TraceThread(id="thread_1")

        # Create a mock conversation thread
        mock_conversation = conversation_thread.ConversationThread()
        mock_conversation.add_user_message("Hello")
        mock_conversation.add_assistant_message("Hi there")

        # Mock the _get_conversation_thread method
        with mock.patch.object(
            self.engine, "_get_conversation_thread", return_value=mock_conversation
        ):
            # Create a metric that raises an exception
            mock_error_metric = mock.MagicMock(
                spec=conversation_thread_metric.ConversationThreadMetric
            )
            mock_error_metric.name = "error_metric"
            mock_error_metric.score.side_effect = ValueError("Test error in metric")

            metrics = [mock_error_metric]

            # Call the method and expect it to handle the error
            with self.assertLogs(
                level="ERROR", logger="opik.evaluation.threads.evaluation_engine"
            ) as log_context:
                self.engine.evaluate_thread(
                    thread=thread,
                    eval_project_name="eval_project",
                    metrics=metrics,
                    trace_input_transform=lambda x: "",
                    trace_output_transform=lambda x: "",
                    max_traces_per_thread=10,
                )

                # Verify error was logged
                self.assertTrue(
                    any(
                        f"Failed to compute metric {mock_error_metric.name}. Score result will be marked as failed."
                        in message
                        for message in log_context.output
                    )
                )

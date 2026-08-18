import atexit
from typing import Any, List, Optional

import opik
from fastapi import APIRouter, Header, HTTPException
from opik.evaluation import evaluate_threads
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.conversation import conversation_thread_metric

from ..opik_factory import make_opik_client
from ..schemas import (
    ThreadEvaluateRequest,
    ThreadEvaluateResponse,
    ThreadEvaluationResult,
    ThreadScore,
)

router = APIRouter(prefix="/threads", tags=["threads"])


# The trace name `evaluate_threads` writes its evaluation under. Hard-coded in
# the SDK, so a test asserting on it is asserting on the SDK's contract.
_EVAL_TRACE_NAME = "evaluation_task"


class _FixedScoreMetric(conversation_thread_metric.ConversationThreadMetric):
    """A conversation metric that returns a caller-supplied constant.

    Every conversation metric Opik ships is an LLM judge, so the thread
    evaluation flow is otherwise unreachable without a provider key and its
    result is never exactly assertable. Scoring from a constant keeps the flow
    itself — thread lookup, conversation assembly, the evaluation trace, the
    feedback score written back onto the thread — under test on any deployment,
    which is the part the E2E suite is here to cover.
    """

    def __init__(self, name: str, value: float, reason: Optional[str] = None) -> None:
        super().__init__(name=name)
        self._value = value
        self._reason = reason

    def score(self, conversation: Any, **kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=self._value, reason=self._reason
        )


@router.post("/evaluate", response_model=ThreadEvaluateResponse, status_code=201)
def evaluate_project_threads(
    body: ThreadEvaluateRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ThreadEvaluateResponse:
    """Run `opik.evaluation.evaluate_threads` over a project's threads.

    Writes a feedback score onto each matched thread and an `evaluation_task`
    trace per thread into `eval_project_name`.
    """
    # evaluate_threads resolves its client through get_global_client() and
    # ignores any locally-constructed one, so bind this request's client as the
    # global — same as routes/experiments.py.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)

    input_key = body.trace_input_key
    output_key = body.trace_output_key

    try:
        evaluation = evaluate_threads(
            project_name=body.project_name,
            filter_string=body.filter_string,
            eval_project_name=body.eval_project_name,
            metrics=[_FixedScoreMetric(body.metric_name, body.score, body.reason)],
            trace_input_transform=lambda payload: payload[input_key],
            trace_output_transform=lambda payload: payload[output_key],
            # Single worker + silent: the caller asserts on the result, and a
            # progress bar in a bridge process only pollutes the test log.
            verbose=0,
            num_workers=1,
        )
        opik.flush_tracker()
        eval_traces = client.search_traces(
            project_name=body.eval_project_name,
            filter_string=f'name = "{_EVAL_TRACE_NAME}"',
            max_results=100,
            wait_for_at_least=len(evaluation.results),
        )
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    if not eval_traces:
        raise HTTPException(
            status_code=500,
            detail=(
                f"No {_EVAL_TRACE_NAME} trace visible in {body.eval_project_name} "
                "after evaluate_threads + flush"
            ),
        )

    results: List[ThreadEvaluationResult] = [
        ThreadEvaluationResult(
            thread_id=str(result.thread_id),
            scores=[
                ThreadScore(name=s.name, value=float(s.value), reason=s.reason)
                for s in result.scores
            ],
        )
        for result in evaluation.results
    ]

    return ThreadEvaluateResponse(
        results=results,
        eval_project_id=str(eval_traces[0].project_id),
        eval_trace_ids=[str(t.id) for t in eval_traces],
    )

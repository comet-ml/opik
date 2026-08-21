import atexit
import time
from typing import Any, List, Optional

import httpx
import opik
from fastapi import APIRouter, Header, HTTPException
from opik.evaluation import evaluate_threads
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.conversation import conversation_thread_metric

from ..opik_factory import make_opik_client
from ..schemas import (
    ThreadsEvaluateRequest,
    ThreadsEvaluateResponse,
    ThreadsEvaluateScore,
)

router = APIRouter(prefix="/threads", tags=["threads"])

# Thread aggregation is eventually consistent: the traces are queryable the
# moment the seed call returns, but the thread row they roll up into appears a
# beat later. evaluate_threads raises EvaluationError on an empty search rather
# than waiting, so the wait belongs here — the same shape as the traces route
# blocking on search_traces(wait_for_at_least=...) before it answers.
_THREAD_VISIBLE_TIMEOUT_S = 60.0
_THREAD_POLL_INTERVAL_S = 1.0


class _FixedScoreCapturingMetric(conversation_thread_metric.ConversationThreadMetric):
    """A conversation metric that scores a constant and records what it was given.

    Two jobs, both of which a real (LLM-backed) metric cannot do in a test:

    - it returns a fixed value, so the flow is assertable with no provider key
      and no model verdict in the loop;
    - it keeps the conversation `score()` received, so a caller can assert on
      the messages the SDK actually built — including whether a message carries
      a `context` key at all, which is invisible from the outside once the
      conversation has been serialized into the evaluation trace's input.
    """

    def __init__(self, name: str, value: float, reason: str) -> None:
        super().__init__(name=name)
        self._value = value
        self._reason = reason
        self.received_conversation: List[dict] = []

    def score(
        self, conversation: Any, **kwargs: Any
    ) -> score_result.ScoreResult:
        # Copied key-by-key rather than kept by reference so a later mutation
        # inside the SDK cannot rewrite what the caller is about to assert on.
        self.received_conversation = [dict(message) for message in conversation]
        return score_result.ScoreResult(
            name=self.name, value=self._value, reason=self._reason
        )


def _wait_for_thread(client: opik.Opik, project_name: str, thread_id: str) -> None:
    from opik.api_objects.threads import threads_client

    threads = threads_client.ThreadsClient(client)
    deadline = time.monotonic() + _THREAD_VISIBLE_TIMEOUT_S
    last_error: Exception | None = None
    while True:
        try:
            found = threads.search_threads(
                project_name=project_name, filter_string=f'id = "{thread_id}"'
            )
            last_error = None
            if found:
                return
        except (httpx.TransportError, httpx.TimeoutException) as exc:
            # This loop exists because thread visibility is eventually
            # consistent, and the window it polls across is exactly when the
            # backend is most likely to drop a connection. Treating a transient
            # transport failure as fatal would abort the evaluation for the very
            # condition the wait was written to absorb, and would surface as a
            # flake with no useful message. Keep polling; the deadline below is
            # still the bound, and a genuine failure just ends there instead.
            #
            # Only transport-level errors are caught. An HTTPStatusError (a 4xx
            # the server meant) is a real answer and must propagate.
            last_error = exc
        if time.monotonic() >= deadline:
            detail = (
                f"Thread {thread_id} not visible in project {project_name} "
                f"after {_THREAD_VISIBLE_TIMEOUT_S}s — nothing to evaluate"
            )
            if last_error is not None:
                # Say so, rather than reporting a clean timeout for what was
                # really a connectivity failure the whole way through.
                detail += f" (last transport error: {last_error!r})"
            raise HTTPException(status_code=500, detail=detail)
        time.sleep(_THREAD_POLL_INTERVAL_S)


@router.post("/evaluate", response_model=ThreadsEvaluateResponse, status_code=201)
def evaluate_thread(
    body: ThreadsEvaluateRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ThreadsEvaluateResponse:
    """Run `opik.evaluation.evaluate_threads` over one seeded thread.

    Scoped to a single thread by `id = "<thread_id>"` so the result is a
    function of this request's own seed and not of whatever else the project
    holds. The evaluation writes a feedback score onto the source thread and an
    `evaluation_task` trace into `eval_project_name`.

    `context_metadata_key` is the one parameter whose ABSENCE is meaningful.
    Set it and `evaluate_threads` is called with a `trace_context_transform`
    reading that key off `trace.metadata`; leave it unset and the argument is
    omitted entirely rather than passed as None — which is the pre-existing
    caller shape, and what the specs assert about (a caller who never asked for
    context must not start seeing a `context` key).

    Returns the metric `scores` the run produced for this thread, and the
    `conversation` the metric's own `score()` received — the latter being the
    only way to see what `evaluate_threads` actually built, since the SDK does
    not otherwise expose it.
    """
    # evaluate_threads resolves its client through get_global_client() and
    # ignores a locally-constructed one, so the request's auth/workspace context
    # has to be bound globally — the same wiring experiments.py needs.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)

    metric = _FixedScoreCapturingMetric(
        name=body.metric_name, value=body.score_value, reason=body.score_reason
    )

    input_key = body.trace_input_key
    output_key = body.trace_output_key
    context_key = body.context_metadata_key

    def _input_transform(trace_input: Any) -> str:
        return trace_input[input_key]

    def _output_transform(trace_output: Any) -> str:
        return trace_output[output_key]

    def _context_transform(trace: Any) -> Optional[List[str]]:
        return (trace.metadata or {}).get(context_key)

    try:
        _wait_for_thread(client, body.project_name, body.thread_id)
        result = evaluate_threads(
            project_name=body.project_name,
            filter_string=f'id = "{body.thread_id}"',
            eval_project_name=body.eval_project_name,
            metrics=[metric],
            trace_input_transform=_input_transform,
            trace_output_transform=_output_transform,
            # Passed only when the caller asked for it. Omitted — not passed as
            # None — otherwise, because "the argument was never supplied" is
            # exactly the pre-existing caller shape the specs assert about.
            **(
                {"trace_context_transform": _context_transform}
                if context_key is not None
                else {}
            ),
            verbose=0,
        )
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    thread_results = [r for r in result.results if r.thread_id == body.thread_id]
    if not thread_results:
        raise HTTPException(
            status_code=500,
            detail=f"evaluate_threads returned no result for thread {body.thread_id}",
        )

    return ThreadsEvaluateResponse(
        thread_id=body.thread_id,
        eval_project_name=body.eval_project_name,
        scores=[
            ThreadsEvaluateScore(name=s.name, value=s.value, reason=s.reason)
            for s in thread_results[0].scores
        ],
        conversation=metric.received_conversation,
    )

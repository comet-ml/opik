from fastapi import APIRouter
from opik.evaluation.metrics.heuristics.readability import Readability

from ..schemas import ReadabilityScoreRequest, ReadabilityScoreResponse

router = APIRouter(prefix="/metrics", tags=["metrics"])


@router.post("/readability-score", response_model=ReadabilityScoreResponse)
def readability_score(body: ReadabilityScoreRequest) -> ReadabilityScoreResponse:
    """Score one text with one locale, reporting the failure instead of raising.

    A metric computed on its own: no client, no workspace, no experiment. It
    touches nothing in the backend, which is why it takes no api-key header.

    The failure path is the point. `textstat.set_lang` does not validate, so
    before opik#8318 an unknown locale surfaced as a bare `KeyError(None)` from
    pyphen's dictionary lookup, several frames from anything naming the cause.
    Reporting `error_type` as the exception's own class name — rather than
    letting the class the caller expects be the only one that can be
    represented — is what lets the caller assert it is a `MetricComputationError`
    naming the language, and see a regression back to `KeyError` as a plain
    assertion diff rather than an opaque 500.
    """
    try:
        result = Readability(language=body.language, track=False).score(output=body.text)
    except Exception as exc:  # noqa: BLE001 - the class name is the assertion
        return ReadabilityScoreResponse(
            language=body.language,
            scored=False,
            error_type=type(exc).__name__,
            error_message=str(exc),
        )

    metadata = result.metadata or {}
    return ReadabilityScoreResponse(
        language=body.language,
        scored=True,
        value=float(result.value),
        reading_ease=float(metadata["flesch_reading_ease"]),
    )

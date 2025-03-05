from ...rest_api.types import span_public
from .. import feedback_score
from . import span_data


# TODO: project_name has to be passed as only the project_id is part of TracePublic and
# we want to avoid an API call to get it. This should be improved
def span_public_to_span_data(
    project_name: str, span_public_: span_public.SpanPublic
) -> span_data.SpanData:
    feedback_scores = span_public_.feedback_scores or []
    feedback_scores_dict = (
        feedback_score.feedback_scores_public_to_feedback_scores_dict(feedback_scores)
    )

    return span_data.SpanData(
        project_name=project_name,
        id=span_public_.id,
        trace_id=span_public_.trace_id,
        parent_span_id=span_public_.parent_span_id,
        name=span_public_.name,
        type=span_public_.type,
        start_time=span_public_.start_time,
        end_time=span_public_.end_time,
        metadata=span_public_.metadata,
        input=span_public_.input,
        output=span_public_.output,
        tags=span_public_.tags,
        usage=span_public_.usage,
        feedback_scores=feedback_scores_dict,
        model=span_public_.model,
        provider=span_public_.provider,
        error_info=span_public_.error_info,
    )

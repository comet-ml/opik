from ...rest_api.types import span_public
from .. import feedback_score
from . import span as span_

# TODO: project_name has to be passed as only the project_id is part of TracePublic and
# we want to avoid an API call to get it. This should be improved
def span_public_to_span_data(
    project_name: str,
    span: span_public.SpanPublic
) -> span_.SpanData:
    
    feedback_scores = span.feedback_scores or []
    feedback_scores_dict = feedback_score.feedback_scores_public_to_feedback_scores_dict(
        feedback_scores
    )

    return span_.SpanData(
        project_name=project_name,
        id=span.id,
        trace_id=span.trace_id,
        parent_span_id=span.parent_span_id,
        name=span.name,
        type=span.type,
        start_time=span.start_time,
        end_time=span.end_time,
        metadata=span.metadata,
        input=span.input,
        output=span.output,
        tags=span.tags,
        usage=span.usage,
        feedback_scores=feedback_scores_dict,
        model=span.model,
        provider=span.provider,
        error_info=span.error_info,
    )

from ...rest_api.types import trace_public
from . import trace as trace_
from .. import feedback_score


# TODO: project_name has to be passed as only the project_id is part of TracePublic and
# we want to avoid an API call to get it. This should be improved.
def trace_public_to_trace_data(
    project_name: str, trace: trace_public.TracePublic
) -> trace_.TraceData:
    feedback_scores = trace.feedback_scores or []
    feedback_scores_dict = (
        feedback_score.feedback_scores_public_to_feedback_scores_dict(feedback_scores)
    )

    return trace_.TraceData(
        project_name=project_name,
        id=trace.id,
        name=trace.name,
        start_time=trace.start_time,
        end_time=trace.end_time,
        metadata=trace.metadata,
        input=trace.input,
        output=trace.output,
        tags=trace.tags,
        feedback_scores=feedback_scores_dict,
        created_by=trace.created_by,
        error_info=trace.error_info,
    )

from ...rest_api.types import trace_public
from . import trace_data
from .. import feedback_score


# TODO: project_name has to be passed as only the project_id is part of TracePublic and
# we want to avoid an API call to get it. This should be improved.
def trace_public_to_trace_data(
    project_name: str, trace_public: trace_public.TracePublic
) -> trace_data.TraceData:
    feedback_scores = trace_public.feedback_scores or []
    feedback_scores_dict = (
        feedback_score.feedback_scores_public_to_feedback_scores_dict(feedback_scores)
    )

    return trace_data.TraceData(
        project_name=project_name,
        id=trace_public.id,
        name=trace_public.name,
        start_time=trace_public.start_time,
        end_time=trace_public.end_time,
        metadata=trace_public.metadata,
        input=trace_public.input,
        output=trace_public.output,
        tags=trace_public.tags,
        feedback_scores=feedback_scores_dict,
        created_by=trace_public.created_by,
        error_info=trace_public.error_info,
    )

from typing import List
from ...rest_api.types import feedback_score_public
from ... import types


def feedback_scores_public_to_feedback_scores_dict(
    feedback_scores_public: List[feedback_score_public.FeedbackScorePublic],
) -> List[types.FeedbackScoreDict]:
    feedback_scores: List[types.FeedbackScoreDict] = []

    for feedback_score in feedback_scores_public:
        feedback_score_dict = types.FeedbackScoreDict(
            name=feedback_score.name,
            value=feedback_score.value,
            category_name=feedback_score.category_name,
            reason=feedback_score.reason,
        )

        feedback_scores.append(feedback_score_dict)

    return feedback_scores

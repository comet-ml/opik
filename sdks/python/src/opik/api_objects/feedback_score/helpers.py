from typing import List
from ...rest_api.types import feedback_score_public
from ... import types
from .. import helpers
from . import feedback_scores_public_to_feedback_scores_dict

def copy_feedback_scores(
    feedback_scores: List[feedback_score_public.FeedbackScorePublic],
    copy_id: bool = False
) -> List[types.FeedbackScoreDict]:
    feedback_scores_dict = feedback_scores_public_to_feedback_scores_dict(
        feedback_scores
    )
    
    if copy_id:
        return feedback_scores_dict
    else:
        feedback_scores_copy = []
        for feedback_score in feedback_scores_dict:
            feedback_score.id = helpers.generate_id()
            feedback_scores_copy.append(feedback_score)
        
        return feedback_scores_copy

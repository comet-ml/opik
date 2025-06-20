from typing import Dict, Any, Optional, List, Union
import logging
import pydantic

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics.llm_judges import parsing_helpers
from opik import exceptions

LOGGER = logging.getLogger(__name__)


class TrajectoryAccuracyResponseFormat(pydantic.BaseModel):
    """Expected format for LLM response when evaluating trajectory accuracy."""
    score: float = pydantic.Field(..., ge=0.0, le=1.0, description="Score between 0.0 and 1.0")
    explanation: str = pydantic.Field(..., min_length=1, description="Detailed explanation for the score")


def trajectory_accuracy_judge(
    example: Dict[str, Any], 
    llm: Optional[base_model.OpikBaseModel] = None
) -> Dict[str, Union[float, str]]:
    """
    Evaluate the accuracy of a ReAct-style agent trajectory.
    
    Assesses whether an agent's sequence of thought/action/observation steps
    demonstrates effective reasoning and appropriate action selection.
    
    Args:
        example: Trajectory data containing:
            - 'trajectory': List of steps with 'thought', 'action', 'observation' keys
            - 'goal': The intended goal or task description  
            - 'final_result': The final outcome achieved
        llm: LLM model interface. Defaults to gpt-4o if None.
    
    Returns:
        Dictionary with 'score' (float 0.0-1.0) and 'explanation' (str).
    
    Example:
        >>> example = {
        ...     'goal': 'Find the weather in Paris',
        ...     'trajectory': [{
        ...         'thought': 'I need to search for weather information',
        ...         'action': 'search_weather(location="Paris")',
        ...         'observation': 'Weather: 22°C, sunny'
        ...     }],
        ...     'final_result': 'The weather in Paris is 22°C and sunny'
        ... }
        >>> result = trajectory_accuracy_judge(example)
        >>> print(f"Score: {result['score']}, Reason: {result['explanation']}")
    """
    
    try:
        # Initialize LLM model
        model = llm or models_factory.get(model_name="gpt-4o")
        
        # Generate evaluation prompt
        prompt = _create_evaluation_prompt(example)
        
        # Get LLM evaluation
        response = model.generate_string(
            input=prompt, 
            response_format=TrajectoryAccuracyResponseFormat
        )
        
        # Parse and return result
        return _parse_evaluation_response(response)
        
    except Exception as e:
        LOGGER.error(f"Trajectory accuracy evaluation failed: {e}", exc_info=True)
        return {
            'score': 0.0,
            'explanation': f"Evaluation failed: {str(e)}"
        }


def _create_evaluation_prompt(example: Dict[str, Any]) -> str:
    """Create the evaluation prompt for trajectory assessment."""
    
    goal = example.get('goal', 'No goal specified')
    trajectory = example.get('trajectory', [])
    final_result = example.get('final_result', 'No result specified')
    
    # Format trajectory steps concisely
    trajectory_steps = _format_trajectory_steps(trajectory)
    
    return f"""You are an expert evaluator of ReAct-style agent trajectories. Assess how effectively the agent reasoned through the problem and selected appropriate actions.

Evaluation Criteria:
1. Reasoning Quality: Logical, relevant thoughts that guide action selection
2. Action Appropriateness: Actions align with thoughts and progress toward the goal  
3. Observation Integration: Effective use of feedback to inform next steps
4. Goal Achievement: Successfully accomplishes the stated objective
5. Efficiency: Reasonable path without unnecessary detours

Scoring Guidelines:
- 0.9-1.0: Excellent reasoning, appropriate actions, achieves goal efficiently
- 0.7-0.8: Good performance with minor issues or inefficiencies
- 0.5-0.6: Adequate but with notable problems in reasoning or actions
- 0.3-0.4: Poor performance, significant flaws but some progress
- 0.0-0.2: Fundamentally flawed, fails to achieve goal

GOAL: {goal}

TRAJECTORY:
{trajectory_steps}

FINAL RESULT: {final_result}

Respond in JSON format:
{{
    "score": <float between 0.0 and 1.0>,
    "explanation": "<specific evaluation referencing trajectory steps>"
}}"""


def _format_trajectory_steps(trajectory: List[Dict[str, Any]]) -> str:
    """Format trajectory steps for prompt inclusion."""
    if not trajectory:
        return "No trajectory steps provided"
    
    formatted_steps = []
    for i, step in enumerate(trajectory, 1):
        thought = step.get('thought', 'No thought')
        action = step.get('action', 'No action') 
        observation = step.get('observation', 'No observation')
        
        formatted_steps.append(
            f"Step {i}:\n"
            f"  Thought: {thought}\n"
            f"  Action: {action}\n"
            f"  Observation: {observation}"
        )
    
    return "\n\n".join(formatted_steps)


def _parse_evaluation_response(content: str) -> Dict[str, Union[float, str]]:
    """Parse LLM response and extract score and explanation."""
    try:
        # Use Opik's parsing helper for robust JSON extraction
        parsed_content = parsing_helpers.extract_json_content_or_raise(content)
        
        score = float(parsed_content["score"])
        explanation = str(parsed_content["explanation"])
        
        # Validate score range
        if not (0.0 <= score <= 1.0):
            raise ValueError(f"Score {score} outside valid range [0.0, 1.0]")
        
        # Validate explanation is not empty
        if not explanation.strip():
            raise ValueError("Explanation cannot be empty")
        
        return {
            'score': score,
            'explanation': explanation
        }
        
    except KeyError as e:
        missing_key = str(e).strip("'\"")
        raise exceptions.MetricComputationError(
            f"Missing required field in response: {missing_key}"
        )
    except (ValueError, TypeError) as e:
        raise exceptions.MetricComputationError(
            f"Invalid response format: {str(e)}"
        )
    except exceptions.JSONParsingError:
        # Re-raise JSON parsing errors as-is
        raise
    except Exception as e:
        raise exceptions.MetricComputationError(
            f"Failed to parse trajectory accuracy evaluation: {str(e)}"
        ) 
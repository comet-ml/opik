from opik import exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers


def parse_evaluation_response(
    content: str, metric_name: str
) -> score_result.ScoreResult:
    """Parse LLM response and extract score and explanation."""
    try:
        parsed_content = parsing_helpers.extract_json_content_or_raise(content)

        score = float(parsed_content["score"])
        explanation = str(parsed_content["explanation"])

        if not (0.0 <= score <= 1.0):
            raise ValueError(f"Score {score} outside valid range [0.0, 1.0]")

        if not explanation.strip():
            raise ValueError("Explanation cannot be empty")

        return score_result.ScoreResult(
            name=metric_name, value=score, reason=explanation
        )

    except KeyError as e:
        missing_key = str(e).strip("'\"")
        raise exceptions.MetricComputationError(
            f"Missing required field in response: {missing_key}"
        )
    except (ValueError, TypeError) as e:
        raise exceptions.MetricComputationError(f"Invalid response format: {str(e)}")
    except exceptions.JSONParsingError:
        raise
    except Exception as e:
        raise exceptions.MetricComputationError(
            f"Failed to parse trajectory accuracy evaluation: {str(e)}"
        )

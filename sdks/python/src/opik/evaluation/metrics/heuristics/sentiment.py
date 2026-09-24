from typing import Any, Optional

from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.metrics.heuristics import _vader_lexicon
from opik.exceptions import MetricComputationError

try:
    import nltk
    from nltk.sentiment import vader
except ImportError:
    nltk = None
    vader = None


class Sentiment(base_metric.BaseMetric):
    """
    A metric that analyzes the sentiment of text using NLTK's VADER sentiment analyzer.

    Returns sentiment scores for positive, neutral, negative, and compound sentiment.
    The compound score is a normalized score between -1.0 (extremely negative) and
    1.0 (extremely positive).

    Args:
        name: The name of the metric. Defaults to "sentiment_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when
            there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import Sentiment
        >>> sentiment_metric = Sentiment()
        >>> result = sentiment_metric.score("I love this product! It's amazing.")
        >>> print(result.value)  # Compound score (e.g., 0.8802)
        >>> print(result.metadata)  # All sentiment scores
    """

    def __init__(
        self,
        name: str = "sentiment_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )

        if nltk is None or vader is None:
            raise ImportError(
                "`nltk` library is required for sentiment analysis. "
                "Install via `pip install nltk` and then download the vader_lexicon: "
                "`python -m nltk.downloader vader_lexicon`."
            )

        # The corpus is fetched once per process rather than on every construction,
        # and a corpus that cannot be had becomes the same actionable ImportError as
        # a missing `nltk` above, instead of whatever the download raised on the way.
        self._analyzer = _vader_lexicon.build_analyzer(
            vader.SentimentIntensityAnalyzer,
            error_message=(
                "`nltk` corpus 'vader_lexicon' is required for sentiment analysis. "
                "Install via `python -m nltk.downloader vader_lexicon`."
            ),
        )

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        """
        Analyze the sentiment of the provided text.

        Args:
            output: The text to analyze for sentiment.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with:
                - value: The compound sentiment score (-1.0 to 1.0)
                - name: The metric name
                - reason: A brief explanation of the sentiment analysis
                - metadata: Dictionary containing all sentiment scores (pos, neu, neg, compound)

        Raises:
            MetricComputationError: If the input text is empty.
        """
        if not output.strip():
            raise MetricComputationError("Empty text provided for sentiment analysis.")

        sentiment_scores = self._analyzer.polarity_scores(output)
        compound_score = sentiment_scores["compound"]

        if compound_score >= 0.05:
            sentiment_category = "positive"
        elif compound_score <= -0.05:
            sentiment_category = "negative"
        else:
            sentiment_category = "neutral"

        return score_result.ScoreResult(
            value=compound_score,
            name=self.name,
            reason=f"Text sentiment analysis: {sentiment_category} (compound score: {compound_score:.4f})",
            metadata=sentiment_scores,
        )

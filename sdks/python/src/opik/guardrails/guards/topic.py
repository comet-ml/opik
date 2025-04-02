from typing import List, Dict, Any, Optional
from . import guard
from .. import schemas
import functools


class Topic(guard.Guard):
    """
    Guard that validates text against a list of topics.

    Can be configured to allow or restrict specific topics.
    """

    def __init__(
        self,
        allowed_topics: Optional[List[str]] = None,
        restricted_topics: Optional[List[str]] = None,
        threshold: float = 0.5,
    ) -> None:
        """
        Initialize a Topic guard.

        Args:
            allowed_topics: List of topics that are allowed (text must match at least one)
            restricted_topics: List of topics that are restricted (text must not match any)
            threshold: Confidence threshold for topic detection (default: 0.5)

        Raises:
            ValueError: If both allowed_topics and restricted_topics are None

        Note:
            At least one of allowed_topics or restricted_topics must be provided.
        """
        if allowed_topics is None and restricted_topics is None:
            raise ValueError("Must specify either allowed_topics or restricted_topics")

        self._allowed_topics = allowed_topics
        self._restricted_topics = restricted_topics
        self._threshold = threshold

    @functools.lru_cache()
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configuration for topic matching.

        Returns:
            List containing the topic validation configuration
        """

        result = []

        if self._allowed_topics:
            result.append(
                {
                    "type": schemas.ValidationType.TOPIC,
                    "config": {
                        "topics": self._allowed_topics,
                        "threshold": self._threshold,
                        "mode": "allow",
                    },
                }
            )

        if self._restricted_topics:
            result.append(
                {
                    "type": schemas.ValidationType.TOPIC,
                    "config": {
                        "topics": self._restricted_topics,
                        "threshold": self._threshold,
                        "mode": "restrict",
                    },
                }
            )

        return result

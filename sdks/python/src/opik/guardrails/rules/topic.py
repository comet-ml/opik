from typing import List, Dict, Any, Optional

from . import rule
from .. import schemas


class Topic(rule.Rule):
    """
    Rule that checks what the text is about: it fails when the text is about a restricted
    topic, or, when allowed topics are given, about none of them.

    Checked by the Opik backend.
    """

    def __init__(
        self,
        allowed_topics: Optional[List[str]] = None,
        restricted_topics: Optional[List[str]] = None,
    ) -> None:
        """
        Initialize a Topic rule.

        Args:
            allowed_topics: Topics the text must be about at least one of.
            restricted_topics: Topics the text must not be about.

        Raises:
            ValueError: If neither list has a topic.
        """
        if not allowed_topics and not restricted_topics:
            raise ValueError("Must specify allowed_topics or restricted_topics")

        self._allowed_topics = allowed_topics
        self._restricted_topics = restricted_topics

    def get_validation_configs(self) -> List[Dict[str, Any]]:
        config: Dict[str, Any] = {}
        if self._allowed_topics:
            config["allowed_topics"] = self._allowed_topics
        if self._restricted_topics:
            config["restricted_topics"] = self._restricted_topics

        return [{"type": schemas.ValidationType.TOPIC, "config": config}]

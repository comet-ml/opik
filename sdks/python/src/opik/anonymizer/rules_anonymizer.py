from typing import List
from .anonymizer import Anonymizer
from .rules import Rule


class RulesAnonymizer(Anonymizer):
    """An anonymizer that applies a list of rules sequentially to text data.

    This class takes a list of Rule objects and applies them to
    anonymize sensitive information in text.
    """

    def __init__(self, rules: List[Rule], max_depth: int = 10):
        """Initialize the RulesAnonymizer with a list of rules.

        Args:
            rules: List of Rule objects to apply for anonymization.
            max_depth: Maximum recursion depth for nested data structures.
        """
        super().__init__(max_depth)
        self.rules = rules

    def anonymize_text(self, data: str) -> str:
        """Apply all rules sequentially to the input text.

        Args:
            data: The text to anonymize.

        Returns:
            The anonymized text after applying all rules.
        """
        result = data
        for rule in self.rules:
            result = rule.apply(result)
        return result

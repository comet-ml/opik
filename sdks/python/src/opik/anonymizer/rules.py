import abc
import re
from typing import Callable


class Rule(abc.ABC):
    """Abstract base class for text anonymization rules.

    Rules define specific patterns or conditions for anonymizing sensitive
    information in text. Subclasses must implement the apply() method to
    define the anonymization logic.
    """

    @abc.abstractmethod
    def apply(self, text: str) -> str:
        pass


class RegexRule(Rule):
    """A rule that uses regular expressions to find and replace patterns in text.

    This rule compiles a regular expression pattern and applies it to input text,
    replacing all matches with a specified replacement string.
    """

    def __init__(self, regex: str, replacement: str):
        """Initialize the regex rule with a pattern and replacement.

        Args:
            regex: Regular expression pattern to match sensitive data.
            replacement: String to replace matched patterns with.
        """
        self.pattern = re.compile(regex)
        self.replacement = replacement

    def apply(self, text: str) -> str:
        return self.pattern.sub(self.replacement, text)


class FunctionRule(Rule):
    """A rule that applies a custom function to anonymize text.

    This rule allows for flexible anonymization by accepting any callable
    that takes a string as input and returns an anonymized string.
    """

    def __init__(self, func: Callable[[str], str]):
        """Initialize the function rule with a custom anonymization function.

        Args:
            func: A callable that takes a string and returns an anonymized version.
        """
        self.func = func

    def apply(self, text: str) -> str:
        return self.func(text)

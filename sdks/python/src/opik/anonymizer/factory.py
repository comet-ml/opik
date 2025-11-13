from typing import Union, List, Dict, Callable, Tuple

from .anonymizer import Anonymizer
from .rules_anonymizer import RulesAnonymizer
from .rules import RegexRule, FunctionRule, Rule

RulesType = Union[
    List[Dict[str, str]],
    List[Tuple[str, str]],
    List[Callable[[str], str]],
    Dict[str, str],
    Tuple[str, str],
    Callable[[str], str],
]


def create_anonymizer(rules: RulesType, max_depth: int = 10) -> Anonymizer:
    """Create an anonymizer with the specified rules.

    Args:
        rules: Rules specification in various formats:
            - Dict with "regex" and "replace" keys for a single regex rule
            - Tuple with (regex, replacement) for a single regex rule
            - Callable that takes a string and returns anonymized string
            - List of any of the above for multiple rules
        max_depth: Maximum recursion depth for nested data structures.

    Returns:
        An Anonymizer instance configured with the specified rules.

    Raises:
        ValueError: If a rule format is invalid.
    """
    rule_objects: List[Rule] = []

    if callable(rules):
        # Single function rule
        rule_objects.append(FunctionRule(rules))
    elif isinstance(rules, dict):
        # Single dictionary rule
        _check_dictionary_rule(rules)
        rule_objects.append(RegexRule(rules["regex"], rules["replace"]))
    elif isinstance(rules, tuple):
        # Single tuple rule
        _check_tuple_rule(rules)
        regex_pattern, replacement = rules
        rule_objects.append(RegexRule(regex_pattern, replacement))
    elif isinstance(rules, list):
        # List of rules
        for rule in rules:
            if callable(rule) and not isinstance(rule, (dict, tuple)):
                rule_objects.append(FunctionRule(rule))
            elif isinstance(rule, dict):
                _check_dictionary_rule(rule)
                rule_objects.append(RegexRule(rule["regex"], rule["replace"]))
            elif isinstance(rule, tuple):
                _check_tuple_rule(rule)
                regex_pattern, replacement = rule
                rule_objects.append(RegexRule(regex_pattern, replacement))
            else:
                raise ValueError(f"Unsupported rule type in list: {type(rule)}")
    else:
        raise ValueError(f"Unsupported rules type: {type(rules)}")

    return RulesAnonymizer(rule_objects, max_depth=max_depth)


def _check_dictionary_rule(rule: Dict[str, str]) -> None:
    if "regex" not in rule or "replace" not in rule:
        raise ValueError("Dictionary rule must have 'regex' and 'replace' keys")


def _check_tuple_rule(rule: Tuple[str, str]) -> None:
    if len(rule) != 2:
        raise ValueError(
            "Tuple rule must have exactly 2 elements: (regex, replacement)"
        )

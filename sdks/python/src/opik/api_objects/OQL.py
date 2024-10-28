"""
This file contains the OQL parser and validator. It is currently limited in scope to only support
simple filters without "and" or "or" operators.
"""

import json

from typing import Any, Dict, Optional

COLUMNS = {
    "name": "string",
    "start_time": "date_time",
    "end_time": "date_time",
    "input": "string",
    "output": "string",
    "metadata": "dictionary",
    "feedback_scores": "feedback_scores_number",
    "tags": "list",
    "usage.total_tokens": "number",
    "usage.prompt_tokens": "number",
    "usage.completion_tokens": "number",
}

SUPPORTED_OPERATORS = {
    "name": ["=", "contains", "not_contains"],
    "start_time": ["=", ">", "<", ">=", "<="],
    "end_time": ["=", ">", "<", ">=", "<="],
    "input": ["=", "contains", "not_contains"],
    "output": ["=", "contains", "not_contains"],
    "metadata": ["=", "contains", ">", "<"],
    "feedback_scores": ["=", ">", "<", ">=", "<="],
    "tags": ["contains"],
    "usage.total_tokens": ["=", ">", "<", ">=", "<="],
    "usage.prompt_tokens": ["=", ">", "<", ">=", "<="],
    "usage.completion_tokens": ["=", ">", "<", ">=", "<="],
}


class OQL:
    def __init__(self, filter_string: Optional[str]):
        self.filter_string = filter_string or ""
        self.parsed_filters = self.parse()

    def _is_valid_field_char(self, char: str) -> bool:
        return char.isalnum() or char == "_"

    def _skip_whitespace(self) -> None:
        while self.i < len(self.filter_string) and self.filter_string[self.i].isspace():
            self.i += 1

    def _parse_field(self) -> Dict[str, Any]:
        # Skip whitespace
        self._skip_whitespace()

        # Parse the field name
        start = self.i
        while self.i < len(self.filter_string) and self._is_valid_field_char(
            self.filter_string[self.i]
        ):
            self.i += 1
        field = self.filter_string[start : self.i]

        # Parse the key if it exists
        if self.filter_string[self.i] == ".":
            self.i += 1
            start = self.i
            while self.i < len(self.filter_string) and self._is_valid_field_char(
                self.filter_string[self.i]
            ):
                self.i += 1
            key = self.filter_string[start : self.i]

            # Keys are only supported for usage, feedback_scores and metadata
            if field not in ["usage", "feedback_scores", "metadata"]:
                raise ValueError(
                    f"Field {field}.{key} is not supported, only the fields {COLUMNS.keys()} are supported."
                )
            elif field == "usage":
                if key not in ["total_tokens", "prompt_tokens", "completion_tokens"]:
                    raise ValueError(
                        f"When querying usage, {key} is not supported, only usage.total_tokens, usage.prompt_tokens and usage.completion_tokens are supported."
                    )
                else:
                    return {
                        "field": f"usage.{key}",
                        "key": "",
                        "type": COLUMNS[f"usage.{key}"],
                    }
            else:
                return {"field": field, "key": key, "type": COLUMNS[field]}

        else:
            return {"field": field, "key": "", "type": COLUMNS[field]}

    def _parse_operator(self, parsed_field: str) -> Dict[str, Any]:
        # Skip whitespace
        self._skip_whitespace()

        # Parse the operator
        if self.filter_string[self.i] == "=":
            operator = "="
            self.i += 1
            return {"operator": operator}

        elif self.filter_string[self.i] in ["<", ">"]:
            if self.filter_string[self.i + 1] == "=":
                operator = f"{self.filter_string[self.i]}="
                self.i += 2
            else:
                operator = self.filter_string[self.i]
                self.i += 1

            if operator not in SUPPORTED_OPERATORS[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {SUPPORTED_OPERATORS[parsed_field]} are supported."
                )
            return {"operator": operator}
        else:
            start = self.i
            while (
                self.i < len(self.filter_string)
                and not self.filter_string[self.i].isspace()
            ):
                self.i += 1

            operator = self.filter_string[start : self.i]
            if operator not in ["contains", "not_contains"]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {SUPPORTED_OPERATORS[parsed_field]} are supported."
                )
            return {"operator": operator}

    def _get_number(self) -> str:
        start = self.i
        while self.i < len(self.filter_string) and self.filter_string[self.i].isdigit():
            self.i += 1
        return self.filter_string[start : self.i]

    def _parse_value(self) -> Dict[str, Any]:
        self._skip_whitespace()

        start = self.i
        if self.filter_string[self.i] == '"':
            self.i += 1
            start = self.i
            while (
                self.i < len(self.filter_string) and self.filter_string[self.i] != '"'
            ):
                self.i += 1

            value = self.filter_string[start : self.i]

            # Add 1 to skip the closing quote and return the value
            self.i += 1
            return {"value": value}
        elif self.filter_string[self.i].isdigit() or self.filter_string[self.i] == "-":
            value = self._get_number()
            if self.i < len(self.filter_string) and self.filter_string[self.i] == ".":
                self.i += 1
                value += "." + self._get_number()

            return {"value": value}
        else:
            raise ValueError(
                f'Invalid value {self.filter_string[start:self.i]}, expected an string in double quotes("value") or a number'
            )

    def parse(self) -> Optional[str]:
        if len(self.filter_string) == 0:
            return None

        self.i = 0

        # Parse fields
        parsed_field = self._parse_field()

        # Parse operators
        parsed_operator = self._parse_operator(parsed_field["field"])

        # Parse values
        parsed_value = self._parse_value()

        # Check for any trailing characters
        self._skip_whitespace()
        if self.i < len(self.filter_string):
            raise ValueError(
                f"Invalid filter string, trailing characters {self.filter_string[self.i:]}"
            )

        return json.dumps([{**parsed_field, **parsed_operator, **parsed_value}])

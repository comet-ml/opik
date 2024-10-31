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


class OpikQueryLanguage:
    """
    This method implements a parser that can be used to convert a filter string into a list of filters that the BE expects.

    For example, this class allows you to convert the query string: `input contains "hello"` into
    `[{'field': 'input', 'operator': 'contains', 'value': 'hello'}]` as expected by the BE.

    When converting a query string into another format, a common approach is:
    1. First convert the string into a series of tokens using a tokenizer
    2. Convert the list of tokens into a Abstract Syntax Tree (AST) using a parser
    3. Traverse the AST and convert it into the desired format using a formatter

    Due to the simple nature of the queries we currently support (no support for and/or operators, etc), we have have
    combined the tokenizer and formatter steps into a single parse method.

    The parse method works by iterating over the string character by character and extracting / validating the tokens.
    """

    def __init__(self, query_string: Optional[str]):
        self.query_string = query_string or ""

        self._cursor = 0
        self.parsed_filters = self._parse()

    def _is_valid_field_char(self, char: str) -> bool:
        return char.isalnum() or char == "_"

    def _skip_whitespace(self) -> None:
        while (
            self._cursor < len(self.query_string)
            and self.query_string[self._cursor].isspace()
        ):
            self._cursor += 1

    def _parse_field(self) -> Dict[str, Any]:
        # Skip whitespace
        self._skip_whitespace()

        # Parse the field name
        start = self._cursor
        while self._cursor < len(self.query_string) and self._is_valid_field_char(
            self.query_string[self._cursor]
        ):
            self._cursor += 1
        field = self.query_string[start : self._cursor]

        # Parse the key if it exists
        if self.query_string[self._cursor] == ".":
            self._cursor += 1
            start = self._cursor
            while self._cursor < len(self.query_string) and self._is_valid_field_char(
                self.query_string[self._cursor]
            ):
                self._cursor += 1
            key = self.query_string[start : self._cursor]

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
        if self.query_string[self._cursor] == "=":
            operator = "="
            self._cursor += 1
            return {"operator": operator}

        elif self.query_string[self._cursor] in ["<", ">"]:
            if self.query_string[self._cursor + 1] == "=":
                operator = f"{self.query_string[self._cursor]}="
                self._cursor += 2
            else:
                operator = self.query_string[self._cursor]
                self._cursor += 1

            if operator not in SUPPORTED_OPERATORS[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {SUPPORTED_OPERATORS[parsed_field]} are supported."
                )
            return {"operator": operator}
        else:
            start = self._cursor
            while (
                self._cursor < len(self.query_string)
                and not self.query_string[self._cursor].isspace()
            ):
                self._cursor += 1

            operator = self.query_string[start : self._cursor]
            if operator not in ["contains", "not_contains"]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {SUPPORTED_OPERATORS[parsed_field]} are supported."
                )
            return {"operator": operator}

    def _get_number(self) -> str:
        start = self._cursor
        while (
            self._cursor < len(self.query_string)
            and self.query_string[self._cursor].isdigit()
        ):
            self._cursor += 1
        return self.query_string[start : self._cursor]

    def _parse_value(self) -> Dict[str, Any]:
        self._skip_whitespace()

        start = self._cursor
        if self.query_string[self._cursor] == '"':
            self._cursor += 1
            start = self._cursor
            while (
                self._cursor < len(self.query_string)
                and self.query_string[self._cursor] != '"'
            ):
                self._cursor += 1

            value = self.query_string[start : self._cursor]

            # Add 1 to skip the closing quote and return the value
            self._cursor += 1
            return {"value": value}
        elif (
            self.query_string[self._cursor].isdigit()
            or self.query_string[self._cursor] == "-"
        ):
            value = self._get_number()
            if (
                self._cursor < len(self.query_string)
                and self.query_string[self._cursor] == "."
            ):
                self._cursor += 1
                value += "." + self._get_number()

            return {"value": value}
        else:
            raise ValueError(
                f'Invalid value {self.query_string[start:self._cursor]}, expected an string in double quotes("value") or a number'
            )

    def _parse(self) -> Optional[str]:
        if len(self.query_string) == 0:
            return None

        # Parse fields
        parsed_field = self._parse_field()

        # Parse operators
        parsed_operator = self._parse_operator(parsed_field["field"])

        # Parse values
        parsed_value = self._parse_value()

        # Check for any trailing characters
        self._skip_whitespace()
        if self._cursor < len(self.query_string):
            raise ValueError(
                f"Invalid filter string, trailing characters {self.query_string[self._cursor:]}"
            )

        return json.dumps([{**parsed_field, **parsed_operator, **parsed_value}])

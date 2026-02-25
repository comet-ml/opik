"""
This file contains the OQL parser and validator. It is currently limited in scope to only support
simple filters without "or" operators.
"""

import json
from abc import ABC, abstractmethod
from typing import Any, Dict, Optional, Tuple, List

STRING_OPERATORS = [
    "=",
    "!=",
    "contains",
    "not_contains",
    "starts_with",
    "ends_with",
    ">",
    "<",
]
DATE_TIME_OPERATORS = ["=", "!=", ">", ">=", "<", "<="]
NUMBER_OPERATORS = ["=", "!=", ">", ">=", "<", "<="]
FEEDBACK_SCORES_OPERATORS = [
    "=",
    "!=",
    ">",
    ">=",
    "<",
    "<=",
    "is_empty",
    "is_not_empty",
]
LIST_OPERATORS = [
    "=",
    "!=",
    "contains",
    "not_contains",
    "is_empty",
    "is_not_empty",
]
DICTIONARY_OPERATORS = [
    "=",
    "!=",
    "contains",
    "not_contains",
    "starts_with",
    "ends_with",
    ">",
    ">=",
    "<",
    "<=",
]


class OQLConfig(ABC):
    """Abstract base class for OQL configuration."""

    @property
    @abstractmethod
    def columns(self) -> Dict[str, str]:
        """Return the supported columns and their types."""
        pass

    @property
    @abstractmethod
    def supported_operators(self) -> Dict[str, List[str]]:
        """Return the supported operators for each column."""
        pass

    @property
    def dictionary_fields(self) -> List[str]:
        """Return fields that support nested key access via dot notation."""
        return ["usage", "feedback_scores", "metadata"]


class TraceOQLConfig(OQLConfig):
    """OQL configuration for trace filtering.

    Based on backend's TraceField enum.
    See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/TraceField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "name": "string",
            "start_time": "date_time",
            "end_time": "date_time",
            "input": "string",
            "output": "string",
            "input_json": "dictionary",
            "output_json": "dictionary",
            "metadata": "dictionary",
            "total_estimated_cost": "number",
            "llm_span_count": "number",
            "tags": "list",
            "usage.total_tokens": "number",
            "usage.prompt_tokens": "number",
            "usage.completion_tokens": "number",
            "feedback_scores": "feedback_scores_number",
            "span_feedback_scores": "feedback_scores_number",
            "duration": "number",
            "thread_id": "string",
            "guardrails": "string",
            "error_info": "error_container",
            "created_at": "date_time",
            "last_updated_at": "date_time",
            "annotation_queue_ids": "list",
            "experiment_id": "string",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "name": STRING_OPERATORS,
            "input": STRING_OPERATORS,
            "output": STRING_OPERATORS,
            "thread_id": STRING_OPERATORS,
            "guardrails": STRING_OPERATORS,
            "experiment_id": STRING_OPERATORS,
            "start_time": DATE_TIME_OPERATORS,
            "end_time": DATE_TIME_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "last_updated_at": DATE_TIME_OPERATORS,
            "total_estimated_cost": NUMBER_OPERATORS,
            "llm_span_count": NUMBER_OPERATORS,
            "usage.total_tokens": NUMBER_OPERATORS,
            "usage.prompt_tokens": NUMBER_OPERATORS,
            "usage.completion_tokens": NUMBER_OPERATORS,
            "duration": NUMBER_OPERATORS,
            "input_json": DICTIONARY_OPERATORS,
            "output_json": DICTIONARY_OPERATORS,
            "metadata": DICTIONARY_OPERATORS,
            "feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "span_feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "tags": LIST_OPERATORS,
            "annotation_queue_ids": LIST_OPERATORS,
            "error_info": ["is_empty", "is_not_empty"],
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return [
            "metadata",
            "input_json",
            "output_json",
            "feedback_scores",
            "span_feedback_scores",
        ]


class SpanOQLConfig(OQLConfig):
    """OQL configuration for span filtering.

    Based on backend's SpanField enum.
    See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/SpanField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "name": "string",
            "start_time": "date_time",
            "end_time": "date_time",
            "input": "string",
            "output": "string",
            "input_json": "dictionary",
            "output_json": "dictionary",
            "metadata": "dictionary",
            "model": "string",
            "provider": "string",
            "total_estimated_cost": "number",
            "tags": "list",
            "usage.total_tokens": "number",
            "usage.prompt_tokens": "number",
            "usage.completion_tokens": "number",
            "feedback_scores": "feedback_scores_number",
            "duration": "number",
            "error_info": "error_container",
            "type": "enum",
            "trace_id": "string",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "name": STRING_OPERATORS,
            "input": STRING_OPERATORS,
            "output": STRING_OPERATORS,
            "model": STRING_OPERATORS,
            "provider": STRING_OPERATORS,
            "trace_id": STRING_OPERATORS,
            "type": ["=", "!="],
            "start_time": DATE_TIME_OPERATORS,
            "end_time": DATE_TIME_OPERATORS,
            "total_estimated_cost": NUMBER_OPERATORS,
            "usage.total_tokens": NUMBER_OPERATORS,
            "usage.prompt_tokens": NUMBER_OPERATORS,
            "usage.completion_tokens": NUMBER_OPERATORS,
            "duration": NUMBER_OPERATORS,
            "input_json": DICTIONARY_OPERATORS,
            "output_json": DICTIONARY_OPERATORS,
            "metadata": DICTIONARY_OPERATORS,
            "feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "tags": LIST_OPERATORS,
            "error_info": ["is_empty", "is_not_empty"],
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return ["metadata", "input_json", "output_json", "feedback_scores"]


class ThreadOQLConfig(OQLConfig):
    """OQL configuration for thread filtering.

    Based on backend's TraceThreadField enum.
    See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/TraceThreadField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "first_message": "string",
            "last_message": "string",
            "number_of_messages": "number",
            "duration": "number",
            "created_at": "date_time",
            "last_updated_at": "date_time",
            "start_time": "date_time",
            "end_time": "date_time",
            "feedback_scores": "feedback_scores_number",
            "status": "enum",
            "tags": "list",
            "annotation_queue_ids": "list",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "first_message": STRING_OPERATORS,
            "last_message": STRING_OPERATORS,
            "number_of_messages": NUMBER_OPERATORS,
            "duration": NUMBER_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "last_updated_at": DATE_TIME_OPERATORS,
            "start_time": DATE_TIME_OPERATORS,
            "end_time": DATE_TIME_OPERATORS,
            "feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "status": ["=", "!="],
            "tags": LIST_OPERATORS,
            "annotation_queue_ids": LIST_OPERATORS,
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return ["feedback_scores"]


class DatasetItemOQLConfig(OQLConfig):
    """OQL configuration for dataset item filtering.

    Based on backend's DatasetItemField enum and FilterQueryBuilder.
    See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/DatasetItemField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        # Maps to DatasetItemField enum values and their FieldType
        return {
            "id": "string",  # FieldType.STRING
            "data": "map",  # FieldType.MAP - supports nested key access
            "full_data": "string",  # FieldType.STRING - toString(data)
            "source": "string",  # FieldType.STRING
            "trace_id": "string",  # FieldType.STRING
            "span_id": "string",  # FieldType.STRING
            "tags": "list",  # FieldType.LIST
            "created_at": "date_time",  # FieldType.DATE_TIME
            "last_updated_at": "date_time",  # FieldType.DATE_TIME
            "created_by": "string",  # FieldType.STRING
            "last_updated_by": "string",  # FieldType.STRING
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "full_data": STRING_OPERATORS,
            "source": STRING_OPERATORS,
            "trace_id": STRING_OPERATORS,
            "span_id": STRING_OPERATORS,
            "created_by": STRING_OPERATORS,
            "last_updated_by": STRING_OPERATORS,
            "data": ["=", "!=", "contains", "not_contains", "starts_with", "ends_with"],
            "tags": LIST_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "last_updated_at": DATE_TIME_OPERATORS,
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        # Fields that support nested key access via dot notation (data.key_name)
        return ["data"]


class PromptVersionOQLConfig(OQLConfig):
    """OQL configuration for prompt version filtering."""

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "commit": "string",
            "template": "string",
            "change_description": "string",
            "metadata": "dictionary",
            "type": "string",
            "tags": "list",
            "created_at": "date_time",
            "created_by": "string",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "commit": STRING_OPERATORS,
            "template": STRING_OPERATORS,
            "change_description": STRING_OPERATORS,
            "metadata": DICTIONARY_OPERATORS,
            "type": ["=", "!="],
            "tags": LIST_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "created_by": STRING_OPERATORS,
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return ["metadata"]


OPERATORS_WITHOUT_VALUES = {"is_empty", "is_not_empty"}

_DEFAULT_CONFIG = TraceOQLConfig()


class OpikQueryLanguage:
    """
    This method implements a parser that can be used to convert a filter string into a list of filters that the BE expects.

    For example, this class allows you to convert the query string: `input contains "hello"` into
    `[{'field': 'input', 'operator': 'contains', 'value': 'hello'}]` as expected by the BE.

    When converting a query string into another format, a common approach is:
    1. First convert the string into a series of tokens using a tokenizer
    2. Convert the list of tokens into an Abstract Syntax Tree (AST) using a parser
    3. Traverse the AST and convert it into the desired format using a formatter

    Due to the simple nature of the queries we currently support (no support for and/or operators, etc.), we have
    combined the tokenizer and formatter steps into a single parse method.

    The parse method works by iterating over the string character by character and extracting / validating the tokens.
    """

    def __init__(self, query_string: Optional[str], config: Optional[OQLConfig] = None):
        self.query_string = query_string or ""
        self._config = config or _DEFAULT_CONFIG

        self._cursor = 0
        self._filter_expressions = self._parse_expressions()
        self.parsed_filters = None
        if self._filter_expressions is not None:
            self.parsed_filters = json.dumps(self._filter_expressions)

    @classmethod
    def for_traces(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        Creates a parser for filtering traces using OQL syntax. Returns an
        OpikQueryLanguage instance preconfigured with TraceOQLConfig that validates
        trace-specific fields. Empty or None query_string yields no filters;
        malformed queries raise ValueError during parsing.
        """
        return cls(query_string, TraceOQLConfig())

    @classmethod
    def for_spans(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        Creates a parser for filtering spans using OQL syntax. Returns an
        OpikQueryLanguage instance preconfigured with SpanOQLConfig that validates
        span-specific fields. Empty or None query_string yields no filters;
        malformed queries raise ValueError during parsing.
        """
        return cls(query_string, SpanOQLConfig())

    @classmethod
    def for_threads(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        Creates a parser for filtering trace threads using OQL syntax. Returns an
        OpikQueryLanguage instance preconfigured with ThreadOQLConfig that validates
        thread-specific fields. Empty or None query_string yields no filters;
        malformed queries raise ValueError during parsing.
        """
        return cls(query_string, ThreadOQLConfig())

    @classmethod
    def for_dataset_items(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        Creates a parser for filtering dataset items using OQL syntax. Use this when working with
        dataset views or filtering items within a dataset. Returns an OpikQueryLanguage instance
        preconfigured with DatasetItemOQLConfig that validates dataset-specific fields like input,
        expected_output, and item metadata. Empty or None query_string yields no filters; malformed
        queries raise ValueError during parsing.
        """
        return cls(query_string, DatasetItemOQLConfig())

    @classmethod
    def for_prompt_versions(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        Creates a parser for filtering prompt versions using OQL syntax. Use this when searching
        or filtering prompt version history. Returns an OpikQueryLanguage instance preconfigured
        with PromptVersionOQLConfig that validates prompt version fields like tags, template,
        commit, metadata, and created_at. Empty or None query_string yields no filters; malformed
        queries raise ValueError during parsing.
        """
        return cls(query_string, PromptVersionOQLConfig())

    def get_filter_expressions(self) -> Optional[List[Dict[str, Any]]]:
        return self._filter_expressions

    def _is_valid_field_char(self, char: str) -> bool:
        return char.isalnum() or char == "_"

    def _is_valid_connector_char(self, char: str) -> bool:
        return char.isalpha()

    def _skip_whitespace(self) -> None:
        while (
            self._cursor < len(self.query_string)
            and self.query_string[self._cursor].isspace()
        ):
            self._cursor += 1

    def _check_escaped_key(self) -> Tuple[bool, str]:
        if self.query_string[self._cursor] in ('"', "'"):
            is_quoted_key = True
            quote_type = self.query_string[self._cursor]
            self._cursor += 1
        else:
            is_quoted_key = False
            quote_type = ""

        return is_quoted_key, quote_type

    def _is_valid_escaped_key_char(self, quote_type: str, start: int) -> bool:
        if self.query_string[self._cursor] != quote_type:
            # Check this isn't the end of the string (means we missed the closing quote)
            if self._cursor + 2 >= len(self.query_string):
                raise ValueError(
                    "Missing closing quote for: " + self.query_string[start - 1 :]
                )

            return True

        # Check if it's an escaped quote (doubled quote)
        if (
            self._cursor + 1 < len(self.query_string)
            and self.query_string[self._cursor + 1] == quote_type
        ):
            # Skip the second quote
            self._cursor += 1
            return True

        return False

    def _parse_connector(self) -> str:
        start = self._cursor
        while self._cursor < len(self.query_string) and self._is_valid_connector_char(
            self.query_string[self._cursor]
        ):
            self._cursor += 1
        connector = self.query_string[start : self._cursor]
        return connector

    def _parse_field(self) -> Dict[str, Any]:
        # Skip whitespace
        self._skip_whitespace()

        columns = self._config.columns
        dictionary_fields = self._config.dictionary_fields

        # Parse the field name
        start = self._cursor
        while self._cursor < len(self.query_string) and self._is_valid_field_char(
            self.query_string[self._cursor]
        ):
            self._cursor += 1
        field = self.query_string[start : self._cursor]

        # Parse the key if it exists
        if (
            self._cursor < len(self.query_string)
            and self.query_string[self._cursor] == "."
        ):
            # Skip the "."
            self._cursor += 1

            # Check if the key is quoted
            is_quoted_key, quote_type = self._check_escaped_key()

            start = self._cursor
            while self._cursor < len(self.query_string) and (
                self._is_valid_field_char(self.query_string[self._cursor])
                or (
                    is_quoted_key and self._is_valid_escaped_key_char(quote_type, start)
                )
            ):
                self._cursor += 1

            key = self.query_string[start : self._cursor]

            # If escaped key, skip the closing quote
            if is_quoted_key:
                key = key.replace(
                    quote_type * 2, quote_type
                )  # Replace doubled quotes with single quotes
                self._cursor += 1

            # Special handling for usage.X fields (trace/span specific)
            # These are treated as flat fields, not dictionary access
            if field == "usage":
                composite_field = f"usage.{key}"
                if composite_field in columns:
                    return {
                        "field": composite_field,
                        "key": "",
                        "type": columns[composite_field],
                    }
                else:
                    raise ValueError(
                        f"When querying usage, {key} is not supported, only usage.total_tokens, usage.prompt_tokens and usage.completion_tokens are supported."
                    )

            # Keys are only supported for dictionary fields
            if field not in dictionary_fields:
                raise ValueError(
                    f"Field {field}.{key} is not supported, only the fields {list(columns.keys())} are supported."
                )
            elif field in columns:
                return {"field": field, "key": key, "type": columns[field]}
            else:
                # defaults to string
                return {"field": field, "key": key, "type": "string"}

        elif field in columns:
            return {"field": field, "key": "", "type": columns[field]}
        else:
            # defaults to string
            return {"field": field, "key": "", "type": "string"}

    def _parse_operator(self, parsed_field: str) -> Dict[str, Any]:
        # Skip whitespace
        self._skip_whitespace()

        supported_operators = self._config.supported_operators

        if parsed_field not in supported_operators:
            parsed_field = "default"

        # Parse the operator
        if self.query_string[self._cursor] == "=":
            operator = "="
            self._cursor += 1
            if operator not in supported_operators[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {supported_operators[parsed_field]} are supported."
                )
            return {"operator": operator}

        elif self.query_string[self._cursor] in ["<", ">"]:
            if self.query_string[self._cursor + 1] == "=":
                operator = f"{self.query_string[self._cursor]}="
                self._cursor += 2
            else:
                operator = self.query_string[self._cursor]
                self._cursor += 1

            if operator not in supported_operators[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {supported_operators[parsed_field]} are supported."
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
            if operator not in supported_operators[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {supported_operators[parsed_field]} are supported."
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

            # TODO: replace with new quote parser used in field parser
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
                f'Invalid value {self.query_string[start : self._cursor]}, expected an string in double quotes("value") or a number'
            )

    def _parse_expressions(self) -> Optional[List[Dict[str, Any]]]:
        if len(self.query_string) == 0:
            return None

        expressions = []

        while True:
            # Parse fields
            parsed_field = self._parse_field()

            # Parse operators
            parsed_operator = self._parse_operator(parsed_field["field"])

            operator_name = parsed_operator.get("operator", "")
            if operator_name in OPERATORS_WITHOUT_VALUES:
                # For operators without values, use empty string as value
                parsed_value = {"value": ""}
            else:
                parsed_value = self._parse_value()

            expressions.append({**parsed_field, **parsed_operator, **parsed_value})

            self._skip_whitespace()

            if self._cursor < len(self.query_string):
                position = self._cursor
                connector = self._parse_connector()

                if connector.lower() == "and":
                    continue
                elif connector.lower() == "or":
                    raise ValueError(
                        "Invalid filter string, OR is not currently supported"
                    )
                else:
                    raise ValueError(
                        f"Invalid filter string, trailing characters {self.query_string[position:]}"
                    )
            else:
                break

        return expressions

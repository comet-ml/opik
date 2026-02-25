import dataclasses
import json
from typing import List, Dict, Callable
from unittest import mock

import pytest

from opik.api_objects.agent_config import type_helpers
from opik.api_objects.prompt.base_prompt import BasePrompt
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail


class TestIsPromptType:
    @pytest.mark.parametrize(
        "py_type",
        [BasePrompt, Prompt, ChatPrompt],
        ids=["BasePrompt", "Prompt", "ChatPrompt"],
    )
    def test_prompt_classes__returns_true(self, py_type):
        assert type_helpers.is_prompt_type(py_type) is True

    @pytest.mark.parametrize(
        "py_type",
        [str, int, float, bool, object, list],
        ids=["str", "int", "float", "bool", "object", "list"],
    )
    def test_non_prompt_types__returns_false(self, py_type):
        assert type_helpers.is_prompt_type(py_type) is False

    def test_custom_subclass_of_base_prompt__returns_true(self):
        class MyPrompt(BasePrompt):
            @property
            def name(self):
                return ""

            @property
            def commit(self):
                return None

            @property
            def version_id(self):
                return ""

            @property
            def metadata(self):
                return None

            @property
            def type(self):
                return None

            @property
            def id(self):
                return None

            @property
            def description(self):
                return None

            @property
            def change_description(self):
                return None

            @property
            def tags(self):
                return None

            def format(self, *args, **kwargs):
                return ""

            def __internal_api__to_info_dict__(self):
                return {}

        assert type_helpers.is_prompt_type(MyPrompt) is True


class TestIsSupportedType:
    @pytest.mark.parametrize(
        "py_type, expected",
        [
            (str, True),
            (int, True),
            (float, True),
            (bool, True),
            (List[str], True),
            (List[int], True),
            (List[float], True),
            (List[bool], True),
            (Dict[str, float], True),
            (Dict[str, bool], True),
            (Dict[str, int], True),
            (Dict[str, str], True),
            (Prompt, True),
            (ChatPrompt, True),
            (BasePrompt, True),
            (PromptVersionDetail, True),
        ],
        ids=[
            "str",
            "int",
            "float",
            "bool",
            "List[str]",
            "List[int]",
            "List[float]",
            "List[bool]",
            "Dict[str,float]",
            "Dict[str,bool]",
            "Dict[str,int]",
            "Dict[str,str]",
            "Prompt",
            "ChatPrompt",
            "BasePrompt",
            "PromptVersionDetail",
        ],
    )
    def test_supported_types__returns_true(self, py_type, expected):
        assert type_helpers.is_supported_type(py_type) is expected

    @pytest.mark.parametrize(
        "py_type",
        [
            list,
            dict,
            Callable,
            object,
            Dict[int, str],
        ],
        ids=["bare_list", "bare_dict", "Callable", "object", "Dict[int,str]"],
    )
    def test_unsupported_types__returns_false(self, py_type):
        assert type_helpers.is_supported_type(py_type) is False

    def test_custom_class__returns_false(self):
        class Foo:
            pass

        assert type_helpers.is_supported_type(Foo) is False

    def test_list_of_custom_class__returns_false(self):
        class Bar:
            pass

        assert type_helpers.is_supported_type(List[Bar]) is False


class TestPythonTypeToBackendType:
    @pytest.mark.parametrize(
        "py_type, expected_backend_type",
        [
            (str, "string"),
            (int, "number"),
            (float, "number"),
            (bool, "string"),
            (List[str], "json"),
            (Dict[str, int], "json"),
        ],
        ids=["str", "int", "float", "bool", "List", "Dict"],
    )
    def test_known_types__returns_correct_backend_type(
        self, py_type, expected_backend_type
    ):
        assert (
            type_helpers.python_type_to_backend_type(py_type) == expected_backend_type
        )

    def test_unsupported_type__raises_type_error(self):
        with pytest.raises(TypeError):
            type_helpers.python_type_to_backend_type(object)

    @pytest.mark.parametrize(
        "py_type",
        [Prompt, ChatPrompt, BasePrompt],
        ids=["Prompt", "ChatPrompt", "BasePrompt"],
    )
    def test_prompt_types__return_prompt(self, py_type):
        assert type_helpers.python_type_to_backend_type(py_type) == "prompt"

    def test_prompt_version_type__returns_prompt_version(self):
        assert (
            type_helpers.python_type_to_backend_type(PromptVersionDetail)
            == "prompt_version"
        )


class TestPythonValueToBackendValue:
    @pytest.mark.parametrize(
        "value, py_type, expected",
        [
            ("hello", str, "hello"),
            (42, int, "42"),
            (0, int, "0"),
            (0.6, float, "0.6"),
            (0.0, float, "0.0"),
            (True, bool, "true"),
            (False, bool, "false"),
        ],
        ids=[
            "str",
            "int",
            "int_zero",
            "float",
            "float_zero",
            "bool_true",
            "bool_false",
        ],
    )
    def test_primitives__serialized_correctly(self, value, py_type, expected):
        assert type_helpers.python_value_to_backend_value(value, py_type) == expected

    @pytest.mark.parametrize(
        "value, py_type, expected_parsed",
        [
            ([1, 2, 3], List[int], [1, 2, 3]),
            ([], List[str], []),
            ({"a": 1, "b": 2}, Dict[str, int], {"a": 1, "b": 2}),
            ({}, Dict[str, str], {}),
        ],
        ids=["list", "empty_list", "dict", "empty_dict"],
    )
    def test_collections__serialized_as_json(self, value, py_type, expected_parsed):
        result = type_helpers.python_value_to_backend_value(value, py_type)
        assert json.loads(result) == expected_parsed

    @pytest.mark.parametrize(
        "py_type",
        [Prompt, ChatPrompt, BasePrompt],
        ids=["Prompt", "ChatPrompt", "BasePrompt"],
    )
    def test_prompt_value__returns_version_id(self, py_type):
        prompt = mock.Mock()
        prompt.__internal_api__version_id__ = "ver-abc"
        assert type_helpers.python_value_to_backend_value(prompt, py_type) == "ver-abc"

    def test_prompt_version_value__returns_id(self):
        version = mock.Mock(spec=PromptVersionDetail)
        version.id = "ver-pv-123"
        assert (
            type_helpers.python_value_to_backend_value(version, PromptVersionDetail)
            == "ver-pv-123"
        )


class TestBackendValueToPythonValue:
    @pytest.mark.parametrize(
        "value, backend_type, py_type, expected",
        [
            ("hello", "string", str, "hello"),
            ("42", "number", int, 42),
            ("42.0", "number", int, 42),
            (42, "number", int, 42),
            ("0.6", "number", float, 0.6),
            (0.6, "number", float, 0.6),
            ("true", "string", bool, True),
            ("false", "string", bool, False),
            (True, "string", bool, True),
        ],
        ids=[
            "str",
            "int_from_str",
            "int_from_float_str",
            "int_native",
            "float_from_str",
            "float_native",
            "bool_true_str",
            "bool_false_str",
            "bool_native",
        ],
    )
    def test_primitives__deserialized_correctly(
        self, value, backend_type, py_type, expected
    ):
        assert (
            type_helpers.backend_value_to_python_value(value, backend_type, py_type)
            == expected
        )

    @pytest.mark.parametrize(
        "value, py_type, expected",
        [
            ("[1, 2, 3]", List[int], [1, 2, 3]),
            ([1, 2, 3], List[int], [1, 2, 3]),
            ('{"a": 1}', Dict[str, int], {"a": 1}),
            ({"a": 1}, Dict[str, int], {"a": 1}),
        ],
        ids=["list_from_json", "list_native", "dict_from_json", "dict_native"],
    )
    def test_collections__deserialized_correctly(self, value, py_type, expected):
        assert (
            type_helpers.backend_value_to_python_value(value, "json", py_type)
            == expected
        )

    def test_none__returns_none(self):
        assert type_helpers.backend_value_to_python_value(None, "string", str) is None

    @pytest.mark.parametrize(
        "py_type",
        [Prompt, ChatPrompt, BasePrompt],
        ids=["Prompt", "ChatPrompt", "BasePrompt"],
    )
    def test_prompt_type__returns_raw_version_id_string(self, py_type):
        result = type_helpers.backend_value_to_python_value(
            "ver-xyz", "prompt", py_type
        )
        assert result == "ver-xyz"

    @pytest.mark.parametrize(
        "py_type",
        [Prompt, ChatPrompt, BasePrompt],
        ids=["Prompt", "ChatPrompt", "BasePrompt"],
    )
    def test_prompt_type__none_value__returns_none(self, py_type):
        assert (
            type_helpers.backend_value_to_python_value(None, "prompt", py_type) is None
        )

    def test_prompt_version_type__returns_raw_version_id_string(self):
        result = type_helpers.backend_value_to_python_value(
            "ver-pv-xyz", "prompt_version", PromptVersionDetail
        )
        assert result == "ver-pv-xyz"

    def test_prompt_version_type__none_value__returns_none(self):
        assert (
            type_helpers.backend_value_to_python_value(
                None, "prompt_version", PromptVersionDetail
            )
            is None
        )


class TestRoundTrip:
    @pytest.mark.parametrize(
        "value, py_type",
        [
            ("hello", str),
            (42, int),
            (0, int),
            (0.6, float),
            (0.0, float),
            (True, bool),
            (False, bool),
        ],
        ids=[
            "str",
            "int",
            "int_zero",
            "float",
            "float_zero",
            "bool_true",
            "bool_false",
        ],
    )
    def test_serialize_then_deserialize__recovers_original(self, value, py_type):
        backend_type = type_helpers.python_type_to_backend_type(py_type)
        backend_value = type_helpers.python_value_to_backend_value(value, py_type)
        restored = type_helpers.backend_value_to_python_value(
            backend_value, backend_type, py_type
        )
        assert restored == value
        assert isinstance(restored, py_type)


class TestExtractDataclassFields:
    def test_basic_dataclass__all_supported_fields_extracted(self):
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"
            count: int = 10
            flag: bool = True

        fields = type_helpers.extract_dataclass_fields(MyConfig)
        names = [f[0] for f in fields]
        assert set(names) == {"temp", "name", "count", "flag"}

    def test_mixed_types__unsupported_fields_filtered_out(self):
        @dataclasses.dataclass
        class MixedConfig:
            temp: float = 0.8
            callback: Callable = lambda: None  # type: ignore

        fields = type_helpers.extract_dataclass_fields(MixedConfig)
        assert len(fields) == 1
        assert fields[0][0] == "temp"

    def test_inherited_dataclass__parent_fields_included(self):
        @dataclasses.dataclass
        class Base:
            base_field: str = "base"

        @dataclasses.dataclass
        class Child(Base):
            child_field: int = 42

        fields = type_helpers.extract_dataclass_fields(Child)
        names = [f[0] for f in fields]
        assert "base_field" in names
        assert "child_field" in names

    def test_non_dataclass__raises_type_error(self):
        class NotADataclass:
            pass

        with pytest.raises(TypeError):
            type_helpers.extract_dataclass_fields(NotADataclass)

    def test_fields_with_defaults__defaults_extracted(self):
        @dataclasses.dataclass
        class WithDefaults:
            temp: float = 0.8
            name: str = "default"

        fields = type_helpers.extract_dataclass_fields(WithDefaults)
        defaults = {f[0]: f[2] for f in fields}
        assert defaults["temp"] == 0.8
        assert defaults["name"] == "default"

    def test_field_without_default__returns_missing_sentinel(self):
        @dataclasses.dataclass
        class NoDefault:
            temp: float

        fields = type_helpers.extract_dataclass_fields(NoDefault)
        assert fields[0][2] is dataclasses.MISSING

    def test_prompt_field__included_in_extracted_fields(self):
        @dataclasses.dataclass
        class WithPrompt:
            temp: float = 0.7
            system_prompt: Prompt = dataclasses.field(default=None)

        fields = type_helpers.extract_dataclass_fields(WithPrompt)
        names = [f[0] for f in fields]
        assert "system_prompt" in names
        assert "temp" in names

    def test_base_prompt_field__included_in_extracted_fields(self):
        @dataclasses.dataclass
        class WithBase:
            p: BasePrompt = dataclasses.field(default=None)

        fields = type_helpers.extract_dataclass_fields(WithBase)
        assert fields[0][0] == "p"

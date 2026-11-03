import pytest
from pydantic import BaseModel

from opik.api_objects.data_helpers import merge_inputs, merge_metadata, merge_outputs


class Payload(BaseModel):
    key: str


@pytest.mark.parametrize(
    "merge, name",
    [(merge_metadata, "metadata"), (merge_inputs, "inputs"), (merge_outputs, "outputs")],
)
def test_non_dict_data_is_rejected_with_the_field_name(merge, name):
    with pytest.raises(ValueError, match=f"{name} must be a dict or a pydantic BaseModel"):
        merge({"existing": 1}, "json string")


def test_pydantic_model_and_dicts_still_merge():
    assert merge_metadata({"a": 1}, Payload(key="value")) == {"a": 1, "key": "value"}
    assert merge_inputs({"a": 1}, {"b": 2}) == {"a": 1, "b": 2}
    assert merge_outputs(None, None) is None


def test_prompts_are_still_attached_to_metadata():
    merged = merge_metadata(None, {"a": 1}, prompts=[{"name": "p"}])

    assert merged == {"a": 1, "opik_prompts": [{"name": "p"}]}

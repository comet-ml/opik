import json

import numpy as np

from opik import jsonable_encoder


def test_encode__self_referencing_dict__cycle_marker() -> None:
    data: dict[str, object] = {}
    data["self"] = data
    encoded = jsonable_encoder.encode(data)
    assert isinstance(encoded["self"], str)
    assert encoded["self"].startswith("<Cyclic reference to dict ")
    json.dumps(encoded)


def test_encode__self_referencing_list__cycle_marker() -> None:
    data: list[object] = []
    data.append(data)
    encoded = jsonable_encoder.encode(data)
    assert isinstance(encoded[0], str)
    assert encoded[0].startswith("<Cyclic reference to list ")
    json.dumps(encoded)


def test_encode__tuple_list_cycle__cycle_marker() -> None:
    nested: list[object] = []
    data = (nested,)
    nested.append(data)
    encoded = jsonable_encoder.encode(data)
    assert isinstance(encoded[0][0], str)
    assert encoded[0][0].startswith("<Cyclic reference to tuple ")
    json.dumps(encoded)


def test_encode__self_referencing_object_array__cycle_marker() -> None:
    data = np.empty(1, dtype=object)
    data[0] = data
    encoded = jsonable_encoder.encode(data)
    assert isinstance(encoded[0], str)
    assert encoded[0].startswith("<Cyclic reference to ndarray ")
    json.dumps(encoded)


def test_encode__shared_list_in_sibling_branches__not_a_cycle() -> None:
    shared = [1, 2]
    assert jsonable_encoder.encode({"left": shared, "right": shared}) == {
        "left": [1, 2],
        "right": [1, 2],
    }


def test_encode__shared_dict_in_sibling_branches__not_a_cycle() -> None:
    shared = {"value": 1}
    assert jsonable_encoder.encode([shared, shared]) == [{"value": 1}, {"value": 1}]

from opik.message_processing import arguments_utils


def test_recursive_shallow_copy__self_referencing_dict__cycle_replaced_with_marker():
    cyclic = {"a": 1}
    cyclic["self"] = cyclic

    copied = arguments_utils.recursive_shallow_copy(cyclic)

    assert copied == {"a": 1, "self": f"<Cyclic reference to dict id={id(cyclic)}>"}


def test_recursive_shallow_copy__self_referencing_list__cycle_replaced_with_marker():
    cyclic = [1]
    cyclic.append(cyclic)

    copied = arguments_utils.recursive_shallow_copy(cyclic)

    assert copied == [1, f"<Cyclic reference to list id={id(cyclic)}>"]


def test_recursive_shallow_copy__cycle_through_another_container__cycle_replaced_with_marker():
    outer = {"a": 1}
    outer["items"] = [outer]

    copied = arguments_utils.recursive_shallow_copy(outer)

    assert copied == {"a": 1, "items": [f"<Cyclic reference to dict id={id(outer)}>"]}


def test_recursive_shallow_copy__container_shared_without_cycle__copied_at_each_position():
    shared = {"b": 2}
    obj = {"first": shared, "second": [shared]}

    copied = arguments_utils.recursive_shallow_copy(obj)

    assert copied == {"first": {"b": 2}, "second": [{"b": 2}]}
    assert copied["first"] is not shared
    assert copied["second"][0] is not shared

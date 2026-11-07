"""Unit tests for recursive_shallow_copy cycle handling (#9435)."""

from opik.message_processing.arguments_utils import recursive_shallow_copy


def test_recursive_shallow_copy__plain_containers__copied_not_shared():
    original = {"a": [1, {"b": 2}], "c": {"d": [3]}}
    copied = recursive_shallow_copy(original)

    assert copied == original
    assert copied is not original
    assert copied["a"] is not original["a"]
    assert copied["a"][1] is not original["a"][1]
    # Shallow: non-container elements are referenced, not copied.
    assert copied["a"][1]["b"] == 2


def test_recursive_shallow_copy__self_referencing_dict__cycle_is_marked():
    d = {}
    d["self"] = d

    assert recursive_shallow_copy(d) == {"self": "<Cyclic reference to dict>"}


def test_recursive_shallow_copy__self_referencing_list__cycle_is_marked():
    lst = []
    lst.append(lst)

    assert recursive_shallow_copy(lst) == ["<Cyclic reference to list>"]


def test_recursive_shallow_copy__nested_cycle__marker_at_the_repeated_container():
    inner = {}
    outer = {"inner": inner}
    inner["outer"] = outer

    assert recursive_shallow_copy(outer) == {
        "inner": {"outer": "<Cyclic reference to dict>"}
    }


def test_recursive_shallow_copy__shared_container_at_sibling_branches__copied_twice():
    # A DAG, not a cycle: the same container appears twice but never on the
    # active path, so both occurrences are copied, not marked.
    shared = {"v": 1}
    original = {"left": shared, "right": shared}

    copied = recursive_shallow_copy(original)

    assert copied == {"left": {"v": 1}, "right": {"v": 1}}
    assert copied["left"] is not shared
    assert copied["right"] is not shared
    assert copied["left"] is not copied["right"]


def test_recursive_shallow_copy__non_container__returned_as_is():
    assert recursive_shallow_copy(42) == 42
    assert recursive_shallow_copy("text") == "text"
    assert recursive_shallow_copy(None) is None

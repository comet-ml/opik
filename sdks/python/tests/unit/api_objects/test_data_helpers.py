from opik.api_objects import data_helpers


def test_merge_tags_both_none():
    """Test merge_tags with both inputs None."""
    result = data_helpers.merge_tags(None, None)
    assert result is None


def test_merge_tags_existing_none():
    """Test merge_tags with existing tags None."""
    result = data_helpers.merge_tags(None, ["new_tag"])
    assert result == ["new_tag"]


def test_merge_tags_new_none():
    """Test merge_tags with new tags None."""
    result = data_helpers.merge_tags(["existing_tag"], None)
    assert result == ["existing_tag"]


def test_merge_tags_no_duplicates():
    """Test merge_tags with no duplicates."""
    result = data_helpers.merge_tags(["tag1"], ["tag2", "tag3"])
    assert result == ["tag1", "tag2", "tag3"]


def test_merge_tags_with_duplicates():
    """Test merge_tags with duplicates."""
    result = data_helpers.merge_tags(["tag1", "tag2"], ["tag2", "tag3"])
    assert result == ["tag1", "tag2", "tag3"]


def test_merge_tags_empty_lists():
    """Test merge_tags with empty lists."""
    result = data_helpers.merge_tags([], [])
    assert result is None


def test_merge_metadata_both_none():
    """Test merge_metadata with both inputs None."""
    result = data_helpers.merge_metadata(None, None)
    assert result is None


def test_merge_metadata_existing_none():
    """Test merge_metadata with existing metadata None."""
    result = data_helpers.merge_metadata(None, {"key": "value"})
    assert result == {"key": "value"}


def test_merge_metadata_new_none():
    """Test merge_metadata with new metadata None."""
    result = data_helpers.merge_metadata({"key": "value"}, None)
    assert result == {"key": "value"}


def test_merge_metadata_no_conflicts():
    """Test merge_metadata with no key conflicts."""
    result = data_helpers.merge_metadata({"key1": "value1"}, {"key2": "value2"})
    assert result == {"key1": "value1", "key2": "value2"}


def test_merge_metadata_with_conflicts():
    """Test merge_metadata with key conflicts (new values win)."""
    result = data_helpers.merge_metadata(
        {"key": "old_value", "other": "kept"}, {"key": "new_value"}
    )
    assert result == {"key": "new_value", "other": "kept"}


def test_merge_metadata_empty_dicts():
    """Test merge_metadata with empty dictionaries."""
    result = data_helpers.merge_metadata({}, {})
    assert result is None

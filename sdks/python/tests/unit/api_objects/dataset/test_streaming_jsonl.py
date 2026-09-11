"""The JSONL loader parses line by line instead of copying the whole document."""

import json
import tracemalloc
from unittest.mock import Mock

from opik.api_objects.dataset import converters
from opik.api_objects.dataset.dataset import Dataset

from .upload_capture import UploadCapture, make_dataset


def _write_jsonl(path, rows):
    path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    return str(path)


def test_stream_from_jsonl_file__parses_each_line(tmp_path):
    rows = [{"input": f"in-{i}", "expected_output": f"out-{i}"} for i in range(5)]
    file_path = _write_jsonl(tmp_path / "items.jsonl", rows)

    items = list(converters.stream_from_jsonl_file(file_path, {}, []))

    assert [item.get_content()["input"] for item in items] == [
        f"in-{i}" for i in range(5)
    ]


def test_stream_from_jsonl_file__blank_lines_skipped(tmp_path):
    path = tmp_path / "items.jsonl"
    path.write_text('{"input": "a"}\n\n   \n{"input": "b"}\n', encoding="utf-8")

    items = list(converters.stream_from_jsonl_file(str(path), {}, []))

    assert len(items) == 2


def test_stream_from_jsonl_file__applies_keys_mapping_and_ignore_keys(tmp_path):
    file_path = _write_jsonl(
        tmp_path / "items.jsonl", [{"Expected output": "x", "drop_me": 1}]
    )

    items = list(
        converters.stream_from_jsonl_file(
            file_path, {"Expected output": "expected_output"}, ["drop_me"]
        )
    )

    assert items[0].expected_output == "x"
    assert "drop_me" not in items[0].get_content()


def test_from_jsonl_file__still_returns_a_list(tmp_path):
    """The eager helper keeps its contract; only its implementation changed."""
    file_path = _write_jsonl(tmp_path / "items.jsonl", [{"input": "a"}, {"input": "b"}])

    result = converters.from_jsonl_file(file_path, {}, [])

    assert isinstance(result, list)
    assert len(result) == 2


def test_stream_from_jsonl_file__is_lazy(tmp_path):
    """Creating the iterator must not read the file."""
    file_path = _write_jsonl(
        tmp_path / "items.jsonl", [{"input": i} for i in range(100)]
    )

    iterator = converters.stream_from_jsonl_file(file_path, {}, [])
    first = next(iterator)

    assert first.get_content()["input"] == 0


def test_stream_from_jsonl_file__peak_memory__flat_in_file_size(tmp_path):
    """The old loader held the parsed list, a re-serialised copy of it, and the re-parse
    of that copy, all at once. Streaming holds one line."""

    def peak_for(count: int) -> int:
        rows = [{"input": {"pad": "x" * 500, "i": i}} for i in range(count)]
        file_path = _write_jsonl(tmp_path / f"items-{count}.jsonl", rows)
        tracemalloc.start()
        for _ in converters.stream_from_jsonl_file(file_path, {}, []):
            pass
        _, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()
        return peak

    small = peak_for(200)
    large = peak_for(800)

    assert large < small * 2, (
        f"Streaming peak grew with file size: {small} -> {large} for 4x the rows"
    )


def test_read_jsonl_from_file__uploads_every_item(tmp_path):
    rows = [{"input": f"in-{i}"} for i in range(3)]
    file_path = _write_jsonl(tmp_path / "items.jsonl", rows)

    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)
    dataset.read_jsonl_from_file(file_path)

    sent = sorted(item["data"]["input"] for item in capture.items)
    assert sent == ["in-0", "in-1", "in-2"]

import math
import pytest

from opik.file_upload.s3_multipart_upload import file_parts_strategy
from opik.file_upload.s3_multipart_upload import s3_upload_error


def test_base_strategy__top_max_file_parts_limits():
    parts_expected = 10
    strategy = file_parts_strategy.BaseFilePartsStrategy(
        file="some_file",
        file_size=file_parts_strategy.MAX_FILE_PART_SIZE * parts_expected,
        max_file_part_size=file_parts_strategy.MAX_FILE_PART_SIZE,
    )

    assert strategy.max_file_part_size == file_parts_strategy.MAX_FILE_PART_SIZE
    parts_number = strategy.calculate()
    assert parts_number == parts_expected


def test_base_strategy__bottom_max_file_parts_limits():
    parts_expected = 10
    strategy = file_parts_strategy.BaseFilePartsStrategy(
        file="some_file",
        file_size=file_parts_strategy.MIN_FILE_PART_SIZE * parts_expected,
        max_file_part_size=file_parts_strategy.MIN_FILE_PART_SIZE,
    )

    assert strategy.max_file_part_size == file_parts_strategy.MIN_FILE_PART_SIZE
    parts_number = strategy.calculate()
    assert parts_number == parts_expected


def test_base_strategy__file_is_too_large():
    strategy = file_parts_strategy.BaseFilePartsStrategy(
        file="some_file",
        file_size=file_parts_strategy.MAX_FILE_PART_SIZE
        * file_parts_strategy.MAX_SUPPORTED_PARTS_NUMBER
        + 1,
        max_file_part_size=file_parts_strategy.MAX_FILE_PART_SIZE,
    )

    with pytest.raises(s3_upload_error.S3UploadErrorFileIsTooLarge):
        strategy.calculate()


def test_base_strategy__happy_path_small_files():
    file_size = 11 * 1024 * 1024
    strategy = file_parts_strategy.BaseFilePartsStrategy(
        file="some_file",
        file_size=file_size,
        max_file_part_size=file_parts_strategy.MIN_FILE_PART_SIZE,
    )

    parts_number = strategy.calculate()
    assert parts_number == math.ceil(file_size / file_parts_strategy.MIN_FILE_PART_SIZE)
    assert strategy.max_file_part_size == file_parts_strategy.MIN_FILE_PART_SIZE
    projected_size = parts_number * strategy.max_file_part_size
    remainder = projected_size - file_size
    assert remainder < file_parts_strategy.MIN_FILE_PART_SIZE

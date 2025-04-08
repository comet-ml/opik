import pytest

from opik.message_processing.file_uploading import mime_type


@pytest.mark.parametrize(
    "file, expected",
    [("test.png", "image/png"), (None, None)],
)
def test_guess_mime_type(file, expected):
    guessed_mime_type = mime_type.guess_mime_type(file)
    assert guessed_mime_type == expected

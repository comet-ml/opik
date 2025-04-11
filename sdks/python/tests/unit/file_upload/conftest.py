import tempfile

import numpy as np
import pytest

FILE_SIZE = 12 * 1024 * 1024


@pytest.fixture
def data_file():
    with tempfile.NamedTemporaryFile(delete=True) as file:
        file.write(np.random.bytes(FILE_SIZE))
        file.seek(0)

        yield file

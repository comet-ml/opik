"""Live image tests use only generated color squares and synthetic questions."""

from collections.abc import Iterator
from uuid import uuid4

import opik
import pytest

from tests.image_fixtures import RED_IMAGE, BLUE_IMAGE


@pytest.fixture(scope="session")
def setup_synthetic_image_dataset(setup_environment: object) -> Iterator[opik.Dataset]:
    client = opik.Opik()
    name = f"native-image-colors-{uuid4().hex[:12]}"
    dataset = client.create_dataset(
        name=name,
        description="Synthetic red/blue RGB squares for native-image transport E2E tests",
    )
    dataset.insert(
        [
            {
                "question": "Which solid color is shown? Answer only red or blue.",
                "image": RED_IMAGE,
                "label": "red",
            },
            {
                "question": "Which solid color is shown? Answer only red or blue.",
                "image": BLUE_IMAGE,
                "label": "blue",
            },
        ]
    )
    try:
        yield dataset
    finally:
        client.delete_dataset(name=name)

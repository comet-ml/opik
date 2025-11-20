"""
DHPR (Driving-Hazard-Prediction-and-Reasoning) dataset loader.

This module provides functions to load the DHPR dataset from HuggingFace
with full multimodal support, including base64-encoded images in structured
content format (OpenAI style).

Dataset: https://huggingface.co/datasets/DHPR/Driving-Hazard-Prediction-and-Reasoning
"""

from io import BytesIO
import base64

import opik
from typing import Any
from PIL import Image

from opik_optimizer.utils.dataset_utils import (
    dataset_name_for_mode,
    default_dataset_name,
    resolve_test_mode_count,
    warn_deprecated_dataset,
)


def driving_hazard(
    *,
    split: str = "train",
    count: int = 50,
    dataset_name: str | None = None,
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """
    Load samples from the DHPR (Driving-Hazard-Prediction-and-Reasoning) dataset.

    Each record contains the hazard-detection question, its answer ("hazard"),
    and multimodal content where the road image is encoded as OpenAI-style
    structured content (text + base64 JPEG). Images can optionally be resized
    and recompressed to control context size.

    Args:
        split: ``"train"`` or ``"test"`` (the DHPR dataset exposes two splits).
        count: Number of samples streamed from the split.
        dataset_name: Optional explicit Opik dataset name.
        test_mode: When True, only a small sample (default 5) is loaded.
        max_image_size: Maximum (width, height) for resizing images. ``None`` keeps
            the original resolution. Defaults to ``(512, 384)``, which yields roughly
            15–20k tokens per image.
        image_quality: JPEG compression quality (1-100). Lower values reduce payload
            size; higher values preserve more detail. Defaults to ``60``.

    Returns:
        `opik.Dataset` populated with multimodal hazard detection samples.
    """
    if count <= 0:
        raise ValueError("count must be a positive integer.")

    normalized_split = split.lower()
    if normalized_split not in {"train", "test"}:
        raise ValueError(
            "Driving hazard dataset exposes only 'train' and 'test' splits."
        )

    preset_name = None
    if normalized_split == "train" and count == 50:
        preset_name = "driving_hazard_50"
    elif normalized_split == "train" and count == 100:
        preset_name = "driving_hazard_100"
    elif normalized_split == "test" and count == 100:
        preset_name = "driving_hazard_test"

    target_name = (
        dataset_name
        or preset_name
        or default_dataset_name(
            base="driving_hazard",
            split=normalized_split,
            start=0,
            count=count,
        )
    )

    return _load_dhpr_dataset(
        dataset_name=target_name,
        nb_items=count,
        test_mode=test_mode,
        split=normalized_split,
        max_image_size=max_image_size,
        image_quality=image_quality,
    )


def driving_hazard_50(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """Legacy helper for 50 training samples."""
    warn_deprecated_dataset("driving_hazard_50", "driving_hazard(count=50)")
    return driving_hazard(
        split="train",
        count=50,
        dataset_name="driving_hazard_50",
        test_mode=test_mode,
        max_image_size=max_image_size,
        image_quality=image_quality,
    )


def driving_hazard_100(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """Legacy helper for 100 training samples."""
    warn_deprecated_dataset("driving_hazard_100", "driving_hazard(count=100)")
    return driving_hazard(
        split="train",
        count=100,
        dataset_name="driving_hazard_100",
        test_mode=test_mode,
        max_image_size=max_image_size,
        image_quality=image_quality,
    )


def driving_hazard_test_split(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """Legacy helper for 100 test samples."""
    warn_deprecated_dataset(
        "driving_hazard_test_split", 'driving_hazard(split="test", count=100)'
    )
    return driving_hazard(
        split="test",
        count=100,
        dataset_name="driving_hazard_test",
        test_mode=test_mode,
        max_image_size=max_image_size,
        image_quality=image_quality,
    )


def _load_dhpr_dataset(
    *,
    dataset_name: str,
    nb_items: int,
    test_mode: bool,
    split: str = "train",
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """
    Internal function to load DHPR dataset with multimodal support.

    Args:
        dataset_name: Logical dataset name used within Opik
        nb_items: Number of items to load
        test_mode: Whether to create a test dataset
        split: Dataset split to load ("train" or "test")
        max_image_size: Maximum image size (width, height) for resizing.
            Set to None to keep original size. Default (512, 384).
        image_quality: JPEG compression quality (1-100). Default 60.

    Returns:
        opik.Dataset with loaded and processed samples
    """
    # Adjust for test mode
    full_name = dataset_name_for_mode(dataset_name, test_mode)
    actual_nb_items = nb_items
    if test_mode:
        actual_nb_items = min(nb_items, resolve_test_mode_count(None))

    # Get or create dataset
    client = opik.Opik()
    dataset = client.get_or_create_dataset(full_name)

    # Check if dataset already has the correct number of items
    items = dataset.get_items()
    if len(items) == actual_nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(
            f"Dataset {full_name} contains {len(items)} items, expected {actual_nb_items}. "
            f"We recommend deleting the dataset and re-creating it."
        )

    # Load from HuggingFace and process
    if len(items) == 0:
        import datasets as ds

        # Load DHPR dataset from HuggingFace
        download_config = ds.DownloadConfig(download_desc=False, disable_tqdm=True)
        ds.disable_progress_bar()

        try:
            hf_dataset = ds.load_dataset(
                "DHPR/Driving-Hazard-Prediction-and-Reasoning",
                streaming=True,
                download_config=download_config,
                trust_remote_code=True,  # May be needed for custom dataset scripts
            )
        except Exception as e:
            # Fallback: try without streaming if streaming fails
            ds.enable_progress_bar()
            raise ValueError(
                f"Failed to load DHPR dataset: {e}. "
                f"Make sure you have internet connection and the dataset is accessible."
            )

        # Process items
        data: list[dict[str, Any]] = []

        for i, item in enumerate(hf_dataset[split]):
            if i >= actual_nb_items:
                break

            processed_item = _process_dhpr_item(
                item=item,
                max_image_size=max_image_size,
                image_quality=image_quality,
            )
            data.append(processed_item)

        ds.enable_progress_bar()

        # Insert into Opik dataset
        dataset.insert(data)

        return dataset


def _encode_pil_to_base64_uri(
    image: Image.Image, format: str = "PNG", quality: int = 85
) -> str:
    """
    Encode a PIL Image to a base64 data URI.

    Args:
        image: PIL Image object
        format: Image format (PNG, JPEG, etc.)
        quality: JPEG quality (1-100), ignored for PNG

    Returns:
        Base64 data URI string (e.g., "data:image/png;base64,iVBORw...")

    Example:
        >>> from PIL import Image
        >>> img = Image.open("photo.jpg")
        >>> data_uri = encode_pil_to_base64_uri(img)
        >>> data_uri[:30]
        'data:image/png;base64,iVBORw0'
    """
    buffer = BytesIO()

    # Save with appropriate parameters
    save_kwargs: dict[str, Any] = {"format": format}
    if format.upper() == "JPEG":
        save_kwargs["quality"] = quality
        save_kwargs["optimize"] = True

    image.save(buffer, **save_kwargs)

    # Encode to base64
    encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")

    # Determine MIME type
    mime_type = f"image/{format.lower()}"
    if format.upper() == "JPEG":
        mime_type = "image/jpeg"

    return f"data:{mime_type};base64,{encoded}"


def _process_dhpr_item(
    item: dict[str, Any],
    max_image_size: tuple[int, int] | None,
    image_quality: int,
) -> dict[str, Any]:
    """
    Process a single DHPR item to create multimodal content.

    Args:
        item: Raw item from HuggingFace dataset
        max_image_size: Maximum image size for resizing
        image_quality: JPEG compression quality (1-100)

    Returns:
        Processed item with structured content
    """
    # Extract fields
    question_id = item.get("question_id")
    question = item.get("question")
    hazard = item.get("hazard")
    pil_image: Image.Image = item.get("image")

    # Resize if needed
    if max_image_size:
        pil_image.thumbnail(max_image_size, Image.Resampling.LANCZOS)

    # Encode to base64 data URI
    image_base64 = _encode_pil_to_base64_uri(
        image=pil_image,
        format="JPEG",
        quality=image_quality,
    )

    # Return processed item
    # The optimizer will use:
    # - question: As the text prompt
    # - image_content: As multimodal structured content
    # - hazard: As the expected output for evaluation
    return {
        "question_id": question_id,
        "question": question,
        "image": image_base64,  # Direct image URI for reference
        "hazard": hazard,  # Expected output (ground truth)
    }

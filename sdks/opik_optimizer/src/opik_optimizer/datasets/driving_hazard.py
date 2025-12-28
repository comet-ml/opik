"""
DHPR (Driving-Hazard-Prediction-and-Reasoning) dataset loader.

This module provides functions to load the DHPR dataset from HuggingFace
with full multimodal support, including base64-encoded images in structured
content format (OpenAI style).

Dataset: https://huggingface.co/datasets/DHPR/Driving-Hazard-Prediction-and-Reasoning
"""

from typing import Any

import opik
from PIL import Image

from opik_optimizer.utils.dataset_utils import (
    dataset_name_for_mode,
    default_dataset_name,
    resolve_test_mode_count,
    warn_deprecated_dataset,
    filter_by_fingerprint,
    record_matches_filter_by,
    FilterBy,
)
from opik_optimizer.utils.image_utils import encode_image_to_base64_uri


def driving_hazard(
    *,
    split: str = "train",
    count: int = 50,
    dataset_name: str | None = None,
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
    filter_by: FilterBy | None = None,
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

    explicit_dataset_name = dataset_name is not None
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
    if not explicit_dataset_name:
        if filter_by:
            target_name = f"{target_name}_filtered_{filter_by_fingerprint(filter_by)}"

    return _load_dhpr_dataset(
        dataset_name=target_name,
        nb_items=count,
        test_mode=test_mode,
        split=normalized_split,
        max_image_size=max_image_size,
        image_quality=image_quality,
        filter_by=filter_by,
    )


def driving_hazard_50(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
    *,
    filter_by: FilterBy | None = None,
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
        filter_by=filter_by,
    )


def driving_hazard_100(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
    *,
    filter_by: FilterBy | None = None,
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
        filter_by=filter_by,
    )


def driving_hazard_test_split(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
    *,
    filter_by: FilterBy | None = None,
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
        filter_by=filter_by,
    )


def _load_dhpr_dataset(
    *,
    dataset_name: str,
    nb_items: int,
    test_mode: bool,
    split: str = "train",
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
    filter_by: FilterBy | None = None,
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

    # Load from HuggingFace and process (len(items) == 0 at this point)
    import datasets as ds

    # Load DHPR dataset from HuggingFace
    download_config = ds.DownloadConfig(download_desc=False, disable_tqdm=True)  # type: ignore[arg-type]
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
    for item in hf_dataset[split]:  # type: ignore[arg-type]
        if filter_by and not record_matches_filter_by(item, filter_by):
            continue
        processed_item = _process_dhpr_item(
            item=item,
            max_image_size=max_image_size,
            image_quality=image_quality,
        )
        data.append(processed_item)
        if len(data) >= actual_nb_items:
            break

    ds.enable_progress_bar()

    # Insert into Opik dataset
    dataset.insert(data)

    return dataset


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
    pil_image: Image.Image = item.get("image")  # type: ignore[assignment]

    # Resize if needed
    if max_image_size:
        pil_image.thumbnail(max_image_size, Image.Resampling.LANCZOS)

    # Encode to base64 data URI
    image_base64 = encode_image_to_base64_uri(
        pil_image, image_format="JPEG", quality=image_quality
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

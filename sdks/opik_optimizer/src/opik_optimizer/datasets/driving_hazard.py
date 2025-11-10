"""
DHPR (Driving-Hazard-Prediction-and-Reasoning) dataset loader.

This module provides functions to load the DHPR dataset from HuggingFace
with full multimodal support, including base64-encoded images in structured
content format (OpenAI style).

Dataset: https://huggingface.co/datasets/DHPR/Driving-Hazard-Prediction-and-Reasoning
"""

from __future__ import annotations

import logging
from typing import Any

import opik
from opik_optimizer.utils.multimodal import (
    encode_pil_to_base64_uri,
    convert_to_structured_content,
    warn_if_python_sdk_outdated,
)

_PillowImage: Any | None
_PIL_IMPORT_ERROR: ModuleNotFoundError | None

try:  # Pillow is optional until an image dataset is loaded
    from PIL import Image as _ImportedPillowImage
except ModuleNotFoundError as exc:
    _PillowImage = None
    _PIL_IMPORT_ERROR = exc
else:
    _PillowImage = _ImportedPillowImage
    _PIL_IMPORT_ERROR = None


logger = logging.getLogger(__name__)


def _ensure_pillow() -> Any:
    if _PillowImage is None:
        raise ModuleNotFoundError(
            "Pillow is required to use the driving_hazard datasets. "
            "Install it with `pip install Pillow` to enable multimodal samples."
        ) from _PIL_IMPORT_ERROR
    return _PillowImage


def driving_hazard_50(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """
    Dataset containing 50 samples from the DHPR driving hazard dataset.

    Each sample includes:
    - question: The hazard detection question
    - image_content: Structured content with text and base64-encoded image
    - hazard: Expected hazard description (ground truth)
    - question_id: Unique identifier

    Args:
        test_mode: If True, creates a test dataset with only 5 samples
        max_image_size: Maximum (width, height) for images. Default (512, 384)
            gives ~15-20k tokens per image. Use None to keep original size.
            Examples: (400, 300) for smaller, (800, 600) for larger.
        image_quality: JPEG compression quality (1-100). Default 60 balances
            quality and size. Lower = smaller files. Higher = better quality.
            Examples: 40-50 (very small), 60-70 (balanced), 85+ (high quality)

    Returns:
        opik.Dataset with multimodal hazard detection samples

    Example:
        >>> # Default settings (recommended for most cases)
        >>> dataset = driving_hazard_50()
        >>>
        >>> # Smaller images for low context
        >>> dataset = driving_hazard_50(max_image_size=(400, 300), image_quality=50)
        >>>
        >>> # Higher quality for detailed analysis
        >>> dataset = driving_hazard_50(max_image_size=(800, 600), image_quality=85)
    """
    return _load_dhpr_dataset(
        dataset_name_prefix="driving_hazard_50",
        nb_items=50,
        test_mode=test_mode,
        split="train",
        max_image_size=max_image_size,
        image_quality=image_quality,
    )


def driving_hazard_100(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """
    Dataset containing 100 samples from the DHPR driving hazard dataset.

    Args:
        test_mode: If True, creates a test dataset with only 5 samples
        max_image_size: Maximum (width, height) for images. Default (512, 384)
            gives ~15-20k tokens per image. Use None to keep original size.
        image_quality: JPEG compression quality (1-100). Default 60 balances
            quality and size. Lower = smaller files. Higher = better quality.

    Returns:
        opik.Dataset with multimodal hazard detection samples
    """
    return _load_dhpr_dataset(
        dataset_name_prefix="driving_hazard_100",
        nb_items=100,
        test_mode=test_mode,
        split="train",
        max_image_size=max_image_size,
        image_quality=image_quality,
    )


def driving_hazard_test_split(
    test_mode: bool = False,
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """
    Dataset containing samples from the DHPR test split.

    Args:
        test_mode: If True, loads only 5 samples; otherwise loads 100 samples
        max_image_size: Maximum (width, height) for images. Default (512, 384)
            gives ~15-20k tokens per image. Use None to keep original size.
        image_quality: JPEG compression quality (1-100). Default 60 balances
            quality and size. Lower = smaller files. Higher = better quality.

    Returns:
        opik.Dataset with multimodal hazard detection samples
    """
    return _load_dhpr_dataset(
        dataset_name_prefix="driving_hazard_test",
        nb_items=100,
        test_mode=test_mode,
        split="test",
        max_image_size=max_image_size,
        image_quality=image_quality,
    )


def _load_dhpr_dataset(
    dataset_name_prefix: str,
    nb_items: int,
    test_mode: bool,
    split: str = "train",
    max_image_size: tuple[int, int] | None = (512, 384),
    image_quality: int = 60,
) -> opik.Dataset:
    """
    Internal function to load DHPR dataset with multimodal support.

    Args:
        dataset_name_prefix: Prefix for the dataset name
        nb_items: Number of items to load
        test_mode: Whether to create a test dataset
        split: Dataset split to load ("train", "test", or "val")
        max_image_size: Maximum image size (width, height) for resizing.
            Set to None to keep original size. Default (512, 384).
        image_quality: JPEG compression quality (1-100). Default 60.

    Returns:
        opik.Dataset with loaded and processed samples
    """
    # Adjust for test mode
    dataset_name = (
        f"{dataset_name_prefix}" if not test_mode else f"{dataset_name_prefix}_test"
    )
    actual_nb_items = nb_items if not test_mode else 5

    # Get or create dataset
    warn_if_python_sdk_outdated()

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)

    # Check if dataset already has the correct number of items
    items = dataset.get_items()
    if len(items) == actual_nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(
            f"Dataset {dataset_name} contains {len(items)} items, expected {actual_nb_items}. "
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
            )
        except Exception:
            # Fallback: try without streaming if streaming fails
            try:
                hf_dataset = ds.load_dataset(
                    "DHPR/Driving-Hazard-Prediction-and-Reasoning",
                    download_config=download_config,
                )
            except Exception as inner_exc:
                ds.enable_progress_bar()
                raise ValueError(
                    f"Failed to load DHPR dataset: {inner_exc}. "
                    "Make sure you have internet connection and the dataset is accessible."
                ) from inner_exc

        # Process items
        data: list[dict[str, Any]] = []

        for i, item in enumerate(hf_dataset[split]):
            if i >= actual_nb_items:
                break

            try:
                processed_item = _process_dhpr_item(
                    item=item,
                    max_image_size=max_image_size,
                    image_quality=image_quality,
                )
                data.append(processed_item)
            except Exception as exc:
                # Log error but continue processing
                logger.warning("Failed to process DHPR item %s: %s", i, exc)
                continue

        ds.enable_progress_bar()

        if len(data) == 0:
            raise ValueError(
                "No items were successfully processed from the DHPR dataset. "
                "Please check the dataset format and image processing."
            )

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
    question_id = item.get("question_id", f"unknown_{hash(str(item))}")
    question = item.get("question", "What hazards do you see in this image?")
    hazard = item.get("hazard", "")
    image_obj = item.get("image")

    # Process image
    if image_obj is None:
        raise ValueError(f"Item {question_id} has no image")

    # Convert HuggingFace image to PIL Image if needed
    PillowImage = _ensure_pillow()
    if hasattr(image_obj, "convert"):
        # Already a PIL Image
        pil_image = image_obj
    elif isinstance(image_obj, dict) and "bytes" in image_obj:
        # Image stored as bytes
        from io import BytesIO

        pil_image = PillowImage.open(BytesIO(image_obj["bytes"]))
    else:
        # Try to convert directly
        pil_image = PillowImage.open(image_obj)

    # Resize if needed
    if max_image_size:
        pil_image.thumbnail(max_image_size, PillowImage.Resampling.LANCZOS)

    # Encode to base64 data URI
    image_uri = encode_pil_to_base64_uri(
        image=pil_image,
        format="JPEG",  # Use JPEG for dashcam photos to reduce size (PNG would preserve exact quality but be ~3-5x larger for similar visual quality)
        quality=image_quality,  # Configurable quality (default 60)
    )

    # Create structured content (OpenAI format)
    # This will be used as input to the model
    image_content = convert_to_structured_content(
        text=question,
        image_uri=image_uri,
        image_detail="auto",
    )

    # Return processed item
    # The optimizer will use:
    # - question: As the text prompt
    # - image_content: As multimodal structured content
    # - hazard: As the expected output for evaluation
    return {
        "question_id": question_id,
        "question": question,
        "image_content": image_content,  # Structured content with image
        "image_uri": image_uri,  # Direct image URI for reference
        "hazard": hazard,  # Expected output (ground truth)
    }

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

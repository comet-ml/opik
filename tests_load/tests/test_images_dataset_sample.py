"""Populate an Opik dataset with CIFAR-10 sample images (URL + base64).

This script loads a small slice of the CIFAR-10 dataset from Hugging Face using
the ``datasets`` library, converts the images into base64 data URIs, and stores
both the encoded data and source URLs (when available) in an Opik dataset. Run
it locally, then open Opik's UI to validate that image attachments render
correctly.

Usage:
    python test_images_dataset_sample.py --workspace <workspace-name>

Environment:
    The script expects OPIC_* environment variables or an `opik` CLI config
    to be present so the Opik Python SDK can authenticate. Install
    dependencies with `pip install datasets pillow`.
"""

from __future__ import annotations

import argparse
import base64
import os
import sys
from io import BytesIO
from pathlib import Path
from typing import Dict, List, Optional

import opik

try:
    import datasets
except ImportError as exc:
    raise SystemExit(
        "The 'datasets' package is required. Install it with 'pip install datasets'."
    ) from exc

try:
    from PIL import Image
except ImportError as exc:  # pragma: no cover - dependency guard
    raise SystemExit(
        "The 'pillow' package is required. Install it with 'pip install pillow'."
    ) from exc

DEFAULT_SAMPLE_COUNT = 8
HF_REPO = "cifar10"

HF_CACHE_DIR = Path(__file__).resolve().parent / ".hf_cache"
HF_CACHE_DIR.mkdir(exist_ok=True)
os.environ.setdefault("HF_DATASETS_CACHE", str(HF_CACHE_DIR))


def encode_base64_uri_from_pil(image) -> str:
    """Convert a PIL image to a base64 data URI."""

    buffer = BytesIO()
    image.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")
    return f"data:image/png;base64,{encoded}"


def _find_image_key(sample: Dict[str, object]) -> Optional[str]:
    for key, value in sample.items():
        if isinstance(value, Image.Image):
            return key
    return None


def build_dataset_items(limit: int, split: str) -> List[Dict[str, str]]:
    """Load CIFAR-10 examples and produce payloads with base64 URIs."""

    dataset = datasets.load_dataset(HF_REPO, split=f"{split}[:{limit}]")
    label_feature = dataset.features.get("label")
    label_names = getattr(label_feature, "names", None) if label_feature else None

    items: List[Dict[str, str]] = []
    for sample in dataset:
        image_key = _find_image_key(sample)
        if not image_key:
            print("Skipping sample without an image field.", file=sys.stderr)
            continue

        image = sample[image_key]
        if not isinstance(image, Image.Image):
            image = Image.fromarray(image)

        label_idx = sample.get("label")
        if label_names and label_idx is not None:
            label = label_names[label_idx]
        else:
            label = str(label_idx)

        data_uri = encode_base64_uri_from_pil(image)

        image_url = None
        image_path = sample.get("img_file_path")
        if image_path:
            image_url = (
                f"https://huggingface.co/datasets/{HF_REPO}/resolve/main/{image_path}"
            )

        payload: Dict[str, str] = {
            "question": "Which CIFAR-10 class best describes this image?",
            "expected_answer": label,
            "image_base64": data_uri,
            "label_name": label,
        }

        if image_url:
            payload["image_url"] = image_url

        items.append(payload)

    return items


def upsert_dataset(workspace: str | None, limit: int, split: str) -> None:
    client = opik.Opik(workspace_name=workspace) if workspace else opik.Opik()

    dataset = client.get_or_create_dataset(
        name="Sample-CIFAR10-Images",
        description=(
            "Sample CIFAR-10 images with both source URLs and base64-encoded "
            "data URIs for validating image support in Opik."
        ),
    )

    dataset_items = build_dataset_items(limit=limit, split=split)
    if not dataset_items:
        print("No dataset items were created; nothing to insert.")
        return

    dataset.insert(dataset_items)

    print(
        "Inserted \"Sample-CIFAR10-Images\" dataset with "
        f"{len(dataset_items)} items. Open the Opik UI to validate image rendering."
    )


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--workspace",
        help="Optional workspace name. Falls back to the default workspace if omitted.",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=DEFAULT_SAMPLE_COUNT,
        help=f"Number of samples to upload (default: {DEFAULT_SAMPLE_COUNT}).",
    )
    parser.add_argument(
        "--split",
        default="train",
        help="Dataset split to sample from (default: train).",
    )
    return parser.parse_args(argv)


def main(argv: List[str]) -> None:
    args = parse_args(argv)
    upsert_dataset(args.workspace, limit=args.count, split=args.split)


if __name__ == "__main__":
    main(sys.argv[1:])

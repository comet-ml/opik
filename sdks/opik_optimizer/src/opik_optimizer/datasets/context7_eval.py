from __future__ import annotations

import json
from importlib import resources
from pathlib import Path
from typing import Any, Dict, List, Optional


DATA_FILENAME = "context7_eval.jsonl"


def load_context7_dataset(path: Optional[Path] = None) -> List[Dict[str, Any]]:
    """Load the synthetic context7 evaluation dataset."""

    dataset_path = (
        Path(path)
        if path is not None
        else resources.files("opik_optimizer.data").joinpath(DATA_FILENAME)
    )

    examples: List[Dict[str, Any]] = []
    with open(dataset_path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            examples.append(json.loads(line))
    return examples


__all__ = ["load_context7_dataset"]

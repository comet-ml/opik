from typing import Any, Dict, Optional, Tuple


def extract_duration_seconds(
    span_input: Optional[Dict[str, Any]],
    output: Dict[str, Any],
) -> Optional[int]:
    candidates = []
    if isinstance(span_input, dict):
        candidates.extend(
            [
                _dig(span_input, ("video_config", "duration_seconds")),
                _dig(span_input, ("config", "video", "duration_seconds")),
            ]
        )

    response = output.get("response") if isinstance(output, dict) else None
    if isinstance(response, dict):
        candidates.append(response.get("duration_seconds"))
    if isinstance(output, dict):
        candidates.append(output.get("duration_seconds"))
        candidates.append(output.get("seconds"))

    for candidate in candidates:
        if candidate is None:
            continue
        try:
            value = int(float(candidate))
            if value > 0:
                return value
        except (TypeError, ValueError):
            continue
    return None


def _dig(payload: Dict[str, Any], path: Tuple[str, ...]) -> Optional[Any]:
    current: Any = payload
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current

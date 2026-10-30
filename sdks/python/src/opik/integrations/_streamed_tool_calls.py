from typing import Any, Dict, List, Optional


class StreamedToolCalls:
    """Reassembles the tool calls of an OpenAI-compatible chat completion stream.

    Providers send each call as fragments: the first one carries ``id``, ``type``
    and the function name, later ones only more ``function.arguments`` text.
    ``index`` says which call a fragment belongs to; a fragment that repeats a
    known ``id`` continues that call even if its ``index`` is missing.
    """

    def __init__(self) -> None:
        self._calls: Dict[int, Dict[str, Any]] = {}
        self._keys_by_call_id: Dict[str, int] = {}

    def add(self, tool_call: Any) -> None:
        payload = tool_call.model_dump(exclude_none=True)
        # `index` only orders fragments inside the stream; a non-streamed
        # response message does not carry it.
        index: Optional[float] = payload.pop("index", None)
        call_id: Optional[str] = payload.get("id")

        key = self._keys_by_call_id.get(call_id) if call_id else None
        if key is None:
            key = int(index) if index is not None else self._next_unindexed_key()
        if call_id:
            self._keys_by_call_id[call_id] = key

        existing = self._calls.get(key)
        if existing is None:
            self._calls[key] = payload
            return

        for field in ("id", "type"):
            if not existing.get(field) and payload.get(field):
                existing[field] = payload[field]

        function = payload.get("function") or {}
        existing_function = existing.setdefault("function", {})
        if not existing_function.get("name") and function.get("name"):
            existing_function["name"] = function["name"]
        if function.get("arguments"):
            existing_function["arguments"] = (
                existing_function.get("arguments") or ""
            ) + function["arguments"]

    def to_list(self) -> List[Dict[str, Any]]:
        # Calls in index order, as OpenAI's aggregator returns them, then the
        # calls opened without an index in arrival order (-1, -2, ...).
        keys = sorted(self._calls, key=lambda key: (key < 0, abs(key)))
        return [self._calls[key] for key in keys]

    def _next_unindexed_key(self) -> int:
        # Kept below every provider index, so a later fragment that does send an
        # index can't land on a call opened without one.
        return min((key for key in self._calls if key < 0), default=0) - 1

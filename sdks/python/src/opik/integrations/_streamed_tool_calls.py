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
        # Argument fragments per call, joined once in ``to_list`` so a long
        # argument string is not copied again for every fragment.
        self._arguments: Dict[int, List[str]] = {}
        self._keys_by_call_id: Dict[str, int] = {}

    def add(self, tool_call: Any) -> None:
        payload = tool_call.model_dump(exclude_none=True)
        # `index` only orders fragments inside the stream; a non-streamed
        # response message does not carry it.
        index: Optional[float] = payload.pop("index", None)
        if index is not None and not float(index).is_integer():
            # Not a position in the stream; truncating it would merge the
            # fragment into an unrelated call.
            index = None
        call_id: Optional[str] = payload.get("id")

        key = self._keys_by_call_id.get(call_id) if call_id else None
        if key is None:
            key = int(index) if index is not None else self._next_unindexed_key()
        if call_id:
            self._keys_by_call_id[call_id] = key

        function = payload.get("function") or {}
        existing = self._calls.get(key)
        if existing is None:
            self._calls[key] = payload
            self._arguments[key] = []
            if function.get("arguments"):
                self._arguments[key].append(function["arguments"])
            return

        for field in ("id", "type"):
            if not existing.get(field) and payload.get(field):
                existing[field] = payload[field]

        existing_function = existing.setdefault("function", {})
        if not existing_function.get("name") and function.get("name"):
            existing_function["name"] = function["name"]
        if function.get("arguments"):
            self._arguments[key].append(function["arguments"])

    def to_list(self) -> List[Dict[str, Any]]:
        # Calls in index order, as OpenAI's aggregator returns them, then the
        # calls opened without an index in arrival order (-1, -2, ...).
        keys = sorted(self._calls, key=lambda key: (key < 0, abs(key)))
        calls = []
        for key in keys:
            call = dict(self._calls[key])
            if self._arguments[key]:
                call["function"] = {
                    **call["function"],
                    "arguments": "".join(self._arguments[key]),
                }
            calls.append(call)
        return calls

    def _next_unindexed_key(self) -> int:
        # Kept below every provider index, so a later fragment that does send an
        # index can't land on a call opened without one.
        return min((key for key in self._calls if key < 0), default=0) - 1

import json
import logging
from typing import Iterable, Set, Type, List, Optional, TypeVar, Protocol


LOGGER = logging.getLogger(__name__)


class _HasID(Protocol):
    id: str


T = TypeVar("T", bound=_HasID)


def read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
    item_ids: Optional[Set[str]] = None,
) -> List[T]:
    result: List[T] = []

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    def process_line(line: bytes) -> bool:
        """Process a single line and apply filtering logic."""
        nonlocal result, item_ids
        item = _parse_stream_line(line=line, item_class=item_class)
        if item is not None:
            if item_ids is not None and item.id not in item_ids:
                return False

            result.append(item)

            if item_ids is not None:
                item_ids.remove(item.id)

            if nb_samples is not None and len(result) == nb_samples:
                return True
        return False

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            if process_line(line):
                return result

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer:
        process_line(buffer)

    return result


def _parse_stream_line(
    line: bytes,
    item_class: Type[T],
) -> Optional[T]:
    try:
        item_dict = json.loads(line.decode("utf-8"))
        item_obj = item_class(**item_dict)
        return item_obj

    except json.JSONDecodeError as e:
        LOGGER.error(f"Error decoding {item_class.__name__}, reason: {e}")
    except (TypeError, ValueError) as e:
        LOGGER.error(f"Error parsing {item_class.__name__}, reason: {e}")
    except Exception as e:
        LOGGER.error(f"Error decoding or parsing {item_class.__name__}, reason: {e}")

    return None

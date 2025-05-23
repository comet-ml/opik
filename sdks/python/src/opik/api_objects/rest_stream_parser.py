import json
import logging
from typing import Callable, Iterable, Type, List, Optional, TypeVar, Any

LOGGER = logging.getLogger(__name__)


# this is the constant for the maximum number of objects sent from the backend side
MAX_ENDPOINT_BATCH_SIZE = 2_000


T = TypeVar("T")


def read_and_parse_full_stream(
    read_source: Callable[[int, int], List[Any]],
    parsed_item_class: Type[T],
    max_results: Optional[int],
    max_endpoint_batch_size: int = MAX_ENDPOINT_BATCH_SIZE,
) -> List[T]:
    result: List[T] = []
    while True:
        if max_results is None:
            current_batch_size = max_endpoint_batch_size
        else:
            amount_left = max_results - len(result)
            current_batch_size = min(amount_left, max_endpoint_batch_size)

        if current_batch_size <= 0:
            # no more data to request
            break

        last_retrieved_id = result[-1].id if len(result) > 0 else None  # type: ignore
        results_stream = read_source(current_batch_size, last_retrieved_id)

        parsed_items = read_and_parse_stream(
            stream=results_stream, item_class=parsed_item_class
        )
        result.extend(parsed_items)

        if current_batch_size > len(parsed_items):
            break

    return result


def read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
) -> List[T]:
    result: List[T] = []

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            item = _parse_stream_line(line=line, item_class=item_class)
            if item is not None:
                result.append(item)

                if nb_samples is not None and len(result) == nb_samples:
                    return result

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer:
        item = _parse_stream_line(line=buffer, item_class=item_class)
        if item is not None:
            result.append(item)

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

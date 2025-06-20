import logging
from typing import Any, Dict, List, Optional

import pydantic

LOGGER = logging.getLogger(__name__)


class SpeechChunksAggregated(pydantic.BaseModel):
    pass


def aggregate(chunks: List[bytes]) -> SpeechChunksAggregated:
    return SpeechChunksAggregated() 
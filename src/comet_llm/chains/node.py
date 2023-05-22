from typing import Optional, Dict
from ..types import JSONEncodable

from .. import datetimes, convert
from . import state

class ChainNode:
    def __init__(
        self,
        input: JSONEncodable,
        category: str,
        name: Optional[str] = None,
        input_metadata: Dict[str, JSONEncodable] = None,
    ):
        self._input = input
        self._category = category
        self._name = name
        self._metadata = dict(input_metadata)

        self._start_timestamp = datetimes.local_timestamp()
        
        self._init_none_fields()

        state.get_global_chain().track_node(self)

    def _init_none_fields(self,):
        self._end_timestamp = None
        self._duration = None
        self._outputs = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self._end_timestamp = datetimes.local_timestamp()
        self._duration = self._end_timestamp - self._start_timestamp

    def set_outputs(
        self, outputs: Dict[str, JSONEncodable], output_metadata: Optional[Dict[str, JSONEncodable]] = None
    ) -> None:
        self._outputs = outputs
        self._metadata.update(output_metadata)

    def as_dict(self):
        return convert.call_data_to_dict(
            prompt=self._input,
            outputs=self._outputs,
            id="the-id",
            metadata=self._metadata,
            prompt_template=None,
            prompt_template_variables=None,
            start_timestamp=self._start_timestamp,
            end_timestamp=self._end_timestamp,
            duration=self._duration
        )
from typing import Dict, Optional

from .. import convert
from ..types import JSONEncodable
from . import node


class Prompt(node.ChainNode):
    def __init__(
        self,
        prompt: JSONEncodable,
        category: str,
        name: Optional[str] = None,
        prompt_template: Optional[JSONEncodable] = None,
        prompt_template_variables: Optional[Dict[str, JSONEncodable]] = None,
        input_metadata: Dict[str, JSONEncodable] = None,
    ):
        self._prompt_template = prompt_template
        self._prompt_template_variables = prompt_template_variables

        super().__init__(
            input=prompt, category=category, name=name, input_metadata=input_metadata
        )

    def as_dict(
        self,
    ) -> Dict[str, JSONEncodable]:
        return convert.call_data_to_dict(
            prompt=self._input,
            outputs=self._outputs,
            id="the-id",
            metadata=self._metadata,
            prompt_template=self._prompt_template,
            prompt_template_variables=self._prompt_template_variables,
            start_timestamp=self._start_timestamp,
            end_timestamp=self._end_timestamp,
            duration=self._duration,
        )

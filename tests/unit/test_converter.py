import pytest

from comet_llm import converter
from comet_llm.types import Timestamp

def test_call_data_to_asset_dict():
    result = converter.call_data_to_dict(
        id="an-id",
        prompt="a-prompt",
        output="an-output",
        metadata="a-metadata",
        prompt_template="prompt-template",
        prompt_variables="prompt-variables",
        timestamp=Timestamp(start=0, end=42),
    )

    assert result == {
        "_id": "an-id",
        "_type": "llm_call",
        "inputs": {
            "final_prompt": "a-prompt",
            "prompt_template": "prompt-template",
            "prompt_variables": "prompt-variables"
        },
        "outputs": "an-output",
        "duration": 42,
        "start_timestamp": 0,
        "end_timestamp": 42,
        "context": [],
        "metadata": "a-metadata"
    }

# {
#       "_id": 990379113499186200,
#       "_type": "llm_call",
#       "inputs": {
#         "final_prompt": "Say this is a test",
#         "prompt_template": "Say {text}",
#         "prompt_variables": {
#           "text": "this is a text"
#         }
#       },
#       "outputs": {
#         "output": "\n\nThis is indeed a test"
#       },
#       "duration": 23.2564,
#       "start_timestamp": 0,
#       "end_timestamp": 23.2564,
#       "context": [],
#       "metadata": {
#         "input": {
#           "type": "completions",
#           "model": "text-davinci-003",
#           "provider": "openai"
#         },
#         "output": {
#           "index": 0,
#           "logprobs": None,
#           "finish_reason": "length"
#         },
#         "usage": {
#           "prompt_tokens": 5,
#           "completion_tokens": 7,
#           "total_tokens": 12
#         }
#       }
# }


# {
#   "_version": 1,
#   "chain_nodes": [
    
#   ],
#   "chain_edges": [],
#   "chain_context": {},
#   "chain_inputs": {
#     "final_prompt": "Say this is a test",
#     "prompt_template": "Say {text}",
#     "prompt_variables": {
#       "text": "this is a text"
#     }
#   },
#   "chain_outputs": {
#     "output": "\n\nThis is indeed a test"
#   },
#   "metadata": {},
#   "start_timestamp": 0,
#   "end_timestamp": 23.2564,
#   "duration": 23.2564
# }
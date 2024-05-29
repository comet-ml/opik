import logging
from typing import TYPE_CHECKING

import comet_llm

from . import verifier

if TYPE_CHECKING:
    import comet_ml

LOGGER = logging.getLogger(__name__)

def test_log_prompt__happyflow(comet_api: "comet_ml.API"):
    # prompt and output are not verified for now

    llm_result = comet_llm.log_prompt(
        prompt="the-input",
        output="the-output",
        duration=42,
        tags=["tag1", "tag2"],
        metadata={
            "metadata-key-1": "metadata-value-1",
            "metadata-key-2": 123,
        }
    )

    print("test_log_prompt__happyflow trace ID: %s" % llm_result.id)

    verifier.verify_trace(
        comet_api,
        llm_result.id,
        expected_duration=42,
        expected_tags=["tag1", "tag2"],
        expected_metadata={
            "metadata-key-1": "metadata-value-1",
            "metadata-key-2": 123,
        }
    )

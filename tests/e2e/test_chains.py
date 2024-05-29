import logging
from typing import TYPE_CHECKING

import comet_llm

from . import verifier

if TYPE_CHECKING:
    import comet_ml

LOGGER = logging.getLogger(__name__)


def test_start_and_end_chain__happyflow(comet_api: "comet_ml.API"):
    # Neither chain nor span inputs and outputs are not verified for now

    comet_llm.start_chain(
        inputs="chain-inputs",
        tags=["tag1", "tag2"],
        metadata={"start-metadata-key": "start-metadata-value"}
    )

    with comet_llm.Span(category="grand-parent", inputs="grand-parent-span-input") as grandparent_span:
        with comet_llm.Span(category="parent", inputs="parent-span-input") as parent_span:
            with comet_llm.Span(category="llm-call", inputs="llm-call-input") as llm_call_span:
                llm_call_span.set_outputs({"llm-call-output-key": "llm-call-output-value"})
            parent_span.set_outputs({"parent-output-key": "parent-output-value"})
        grandparent_span.set_outputs({"grandparent-output-key": "grandparent-output-value"})

    llm_result = comet_llm.end_chain(
        outputs="chain-outputs",
        metadata={"end-metadata-key": "end-metadata-value"}
    )

    print("test_start_and_end_chain__happyflow trace ID: %s" % llm_result.id)

    verifier.verify_trace(
        comet_api,
        llm_result.id,
        expected_tags=["tag1", "tag2"],
        expected_metadata={
            "start-metadata-key": "start-metadata-value",
            "end-metadata-key": "end-metadata-value",
        }
    )



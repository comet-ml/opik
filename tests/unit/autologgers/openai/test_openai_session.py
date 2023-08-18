import pytest

from testix import *

from comet_llm.autologgers.openai import context

# @pytest.fixture(autouse=True)
# def mock_imports(patch_module):
#     patch_module(context, "chain")


# def _construct(experiment_info_):
#     with Scenario() as s:
#         s.experiment_info.get() >> experiment_info_
#         tested = context.OpenAISession()
    
#     return tested

# def test_get_chain__chain_is_created_once__chain_returned():
#     tested = _construct("experiment-info")
    
#     with Scenario() as s:
#         s.chain.Chain(
#             inputs="the-inputs",
#             metadata="the-metadata",
#             experiment_info="experiment-info",
#             tags="the-tags"
#         ) >> "the-chain"
        
#         assert tested.get_chain("the-inputs", metadata="the-metadata", tags="the-tags") == "the-chain"
#         assert tested.get_chain("the-inputs", metadata="the-metadata", tags="the-tags") == "the-chain"


# def test_end_chain__happyflow():
#     tested = _construct("experiment-info")
    
#     with Scenario() as s:
#         s.chain.Chain(inputs="the-inputs") >> "the-chain"
#         tested.get_chain("the-inputs")
#         tested.end_chain()
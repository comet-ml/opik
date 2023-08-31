import pytest
import random
import threading
import time
import comet_llm.chains.state
from comet_llm.chains import chain
from typing import Dict, Any


def chain_execution(thread_id: int, result_chains: Dict[int, chain.Chain]) -> None:
    comet_llm.start_chain(inputs=thread_id)
    with comet_llm.Span(category="grand-parent", inputs=thread_id):
        time.sleep(random.random())
        with comet_llm.Span(category="parent", inputs=thread_id):
            time.sleep(random.random())
            with comet_llm.Span(category="llm-call", inputs=thread_id):
                time.sleep(random.random())
            time.sleep(random.random())
        time.sleep(random.random())
    comet_llm.end_chain(outputs=thread_id)

    chain = comet_llm.chains.state.get_global_chain()
  
    result_chains[thread_id] = chain


def chain_is_consistent(thread_id: int, chain_: chain.Chain) -> bool:
    chain_data = chain_.as_dict()

    CORRECT_INPUTS = {"input": thread_id}
    CORRECT_OUTPUTS = {"output": thread_id}

    spans_data = [span for span in chain_data["chain_nodes"]]
    spans_consistent = all(span_data["inputs"] == CORRECT_INPUTS for span_data in spans_data)

    chain_consistent = (
        spans_consistent
        and chain_data["chain_inputs"] == CORRECT_INPUTS
        and chain_data["chain_outputs"] == CORRECT_OUTPUTS
    )

    return chain_consistent


def run_chain_executions(threads: int) -> Dict[int, chain.Chain]:
    all_threads = []
    results = {}

    for i in range(threads):
        thread = threading.Thread(target=chain_execution, args=(i, results))
        thread.start()
        all_threads.append(thread)
    for t in all_threads:
        t.join(10)
    
    return results


@pytest.mark.forked
def test_chains_run_in_multiple_threads__each_thread_has_own_consistent_chain():
    THREADS_AMOUNT = 5
    threads_chains = run_chain_executions(threads=THREADS_AMOUNT)
    
    one_chain_per_thread = len(set(id(chain) for chain in threads_chains.values())) == THREADS_AMOUNT
    assert one_chain_per_thread

    for thread_id, chain in threads_chains.items():
        assert chain_is_consistent(thread_id, chain)

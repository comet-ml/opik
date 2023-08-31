import random
import threading
import time
from typing import Any, Dict

import pytest

import comet_llm.chains.state
from comet_llm.chains import chain


def chain_execution(thread_id: int, result_chains: Dict[int, chain.Chain]) -> None:
    comet_llm.start_chain(
        inputs=thread_id,
        api_key="fake-api-key",
    )
    with comet_llm.Span(category="grand-parent", inputs=thread_id):
        time.sleep(random.random())
        with comet_llm.Span(category="parent", inputs=thread_id):
            time.sleep(random.random())
            with comet_llm.Span(category="llm-call", inputs=thread_id):
                time.sleep(random.random())
            time.sleep(random.random())
        time.sleep(random.random())

    # comet_llm.end_chain(...) is not called to avoid logging real data

    chain = comet_llm.chains.state.get_global_chain()

    result_chains[thread_id] = chain


def assert_chain_is_consistent(thread_id: int, chain_: chain.Chain) -> bool:
    chain_data = chain_.as_dict()

    CORRECT_INPUTS = {"input": thread_id}
    CORRECT_SPAN_CATEGORIES = ["grand-parent", "parent", "llm-call"]

    spans_data = [span for span in chain_data["chain_nodes"]]

    assert len(spans_data) == len(CORRECT_SPAN_CATEGORIES)

    for span_data, correct_span_category in zip(spans_data, CORRECT_SPAN_CATEGORIES):
        assert span_data["inputs"] == CORRECT_INPUTS
        assert span_data["category"] == correct_span_category

    assert chain_data["chain_inputs"] == CORRECT_INPUTS


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

    assert len(set(id(chain) for chain in threads_chains.values())) == THREADS_AMOUNT

    for thread_id, chain in threads_chains.items():
        assert_chain_is_consistent(thread_id, chain)

import random

import pytest

from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
    _deap_crossover_chunking_strategy,
    _deap_crossover_word_level,
)


class TestDeapCrossoverChunkingStrategy:
    """Tests for _deap_crossover_chunking_strategy function."""

    def test_swaps_chunks_at_crossover_point(self) -> None:
        random.seed(42)

        msg1 = "First chunk. Second chunk. Third chunk."
        msg2 = "Alpha chunk. Beta chunk. Gamma chunk."

        child1, child2 = _deap_crossover_chunking_strategy(msg1, msg2)

        assert child1.endswith(".")
        assert child2.endswith(".")

        has_first = "First" in child1 or "First" in child2
        has_alpha = "Alpha" in child1 or "Alpha" in child2
        assert has_first and has_alpha, "Children should contain chunks from both parents"

    def test_raises_when_not_enough_chunks(self) -> None:
        msg1 = "Single chunk only"
        msg2 = "Another single chunk"

        with pytest.raises(ValueError, match="Not enough chunks"):
            _deap_crossover_chunking_strategy(msg1, msg2)

    def test_handles_minimum_two_chunks(self) -> None:
        random.seed(42)

        msg1 = "First part. Second part."
        msg2 = "Part A. Part B."

        child1, child2 = _deap_crossover_chunking_strategy(msg1, msg2)

        assert child1.endswith(".")
        assert child2.endswith(".")

    def test_preserves_sentence_structure(self) -> None:
        random.seed(42)

        msg1 = "Be helpful. Be concise. Be accurate."
        msg2 = "Answer questions. Provide examples. Stay focused."

        child1, child2 = _deap_crossover_chunking_strategy(msg1, msg2)

        assert child1.endswith(".")
        assert child2.endswith(".")
        assert child1.count(".") >= 2, "Child 1 should have multiple sentences"
        assert child2.count(".") >= 2, "Child 2 should have multiple sentences"

    def test_different_seeds_produce_different_results(self) -> None:
        msg1 = "A. B. C. D."
        msg2 = "W. X. Y. Z."

        random.seed(1)
        result1 = _deap_crossover_chunking_strategy(msg1, msg2)

        random.seed(2)
        result2 = _deap_crossover_chunking_strategy(msg1, msg2)

        assert result1[0] and result1[1]
        assert result2[0] and result2[1]


class TestDeapCrossoverWordLevel:
    """Tests for _deap_crossover_word_level function."""

    def test_swaps_words_at_crossover_point(self) -> None:
        random.seed(42)

        msg1 = "one two three four"
        msg2 = "alpha beta gamma delta"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        assert child1
        assert child2
        assert isinstance(child1, str)
        assert isinstance(child2, str)

    def test_handles_short_inputs(self) -> None:
        random.seed(42)

        msg1 = "one"
        msg2 = "alpha"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)
        assert child1
        assert child2

    def test_preserves_word_count_approximately(self) -> None:
        random.seed(42)

        msg1 = "one two three four five"
        msg2 = "alpha beta gamma delta epsilon"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        assert 1 <= len(child1.split()) <= 10
        assert 1 <= len(child2.split()) <= 10


class TestCrossoverIntegration:
    """Integration tests for crossover operations."""

    def test_chunking_fallback_to_word_level(self) -> None:
        msg1 = "no periods here"
        msg2 = "also no periods"

        with pytest.raises(ValueError):
            _deap_crossover_chunking_strategy(msg1, msg2)

        random.seed(42)
        child1, child2 = _deap_crossover_word_level(msg1, msg2)
        assert child1 and child2

    def test_both_strategies_produce_valid_output(self) -> None:
        random.seed(42)

        msg1_chunks = "First sentence. Second sentence. Third sentence."
        msg2_chunks = "Alpha sentence. Beta sentence. Gamma sentence."

        c1, c2 = _deap_crossover_chunking_strategy(msg1_chunks, msg2_chunks)
        assert c1 and c2
        assert isinstance(c1, str) and isinstance(c2, str)

        msg1_words = "one two three four"
        msg2_words = "alpha beta gamma delta"

        w1, w2 = _deap_crossover_word_level(msg1_words, msg2_words)
        assert w1 and w2
        assert isinstance(w1, str) and isinstance(w2, str)


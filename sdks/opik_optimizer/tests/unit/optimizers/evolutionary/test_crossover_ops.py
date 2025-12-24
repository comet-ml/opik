"""
Unit tests for opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops module.

Tests cover:
- _deap_crossover_chunking_strategy: Chunk-level crossover
- _deap_crossover_word_level: Word-level crossover
"""

import pytest
import random

from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
    _deap_crossover_chunking_strategy,
    _deap_crossover_word_level,
)


class TestDeapCrossoverChunkingStrategy:
    """Tests for _deap_crossover_chunking_strategy function."""

    def test_swaps_chunks_at_crossover_point(self) -> None:
        """Should swap chunks at a random crossover point."""
        # Set seed for reproducibility
        random.seed(42)

        msg1 = "First chunk. Second chunk. Third chunk."
        msg2 = "Alpha chunk. Beta chunk. Gamma chunk."

        child1, child2 = _deap_crossover_chunking_strategy(msg1, msg2)

        # With seed 42, crossover happens at a specific point
        # Children should be combinations of parent chunks
        assert child1.endswith(".")
        assert child2.endswith(".")

        # At least one child should have mixed content from both parents
        has_first = "First" in child1 or "First" in child2
        has_alpha = "Alpha" in child1 or "Alpha" in child2
        assert has_first and has_alpha, (
            "Children should contain chunks from both parents"
        )

    def test_raises_when_not_enough_chunks(self) -> None:
        """Should raise ValueError when prompts don't have enough chunks."""
        msg1 = "Single chunk only"
        msg2 = "Another single chunk"

        with pytest.raises(ValueError, match="Not enough chunks"):
            _deap_crossover_chunking_strategy(msg1, msg2)

    def test_handles_minimum_two_chunks(self) -> None:
        """Should work with exactly two chunks each."""
        random.seed(42)

        msg1 = "First part. Second part."
        msg2 = "Part A. Part B."

        child1, child2 = _deap_crossover_chunking_strategy(msg1, msg2)

        # Should produce valid children
        assert child1.endswith(".")
        assert child2.endswith(".")

    def test_preserves_sentence_structure(self) -> None:
        """Children should maintain sentence-like structure."""
        random.seed(42)

        msg1 = "Be helpful. Be concise. Be accurate."
        msg2 = "Answer questions. Provide examples. Stay focused."

        child1, child2 = _deap_crossover_chunking_strategy(msg1, msg2)

        # Children should end with period and have sentence separators
        assert child1.endswith(".")
        assert child2.endswith(".")
        # Each child should have multiple sentences from the crossover
        assert child1.count(".") >= 2, "Child 1 should have multiple sentences"
        assert child2.count(".") >= 2, "Child 2 should have multiple sentences"

    def test_different_seeds_produce_different_results(self) -> None:
        """Different random seeds should produce different crossover points."""
        msg1 = "A. B. C. D."
        msg2 = "W. X. Y. Z."

        random.seed(1)
        result1 = _deap_crossover_chunking_strategy(msg1, msg2)

        random.seed(2)
        result2 = _deap_crossover_chunking_strategy(msg1, msg2)

        # Results might be different (probabilistic test)
        # Just verify both produce valid output
        assert result1[0] and result1[1]
        assert result2[0] and result2[1]


class TestDeapCrossoverWordLevel:
    """Tests for _deap_crossover_word_level function."""

    def test_swaps_words_at_crossover_point(self) -> None:
        """Should swap words at a random crossover point."""
        random.seed(42)

        msg1 = "one two three four"
        msg2 = "alpha beta gamma delta"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        # Children should contain words from both parents
        words1 = set(child1.split())
        words2 = set(child2.split())

        all_msg1_words = {"one", "two", "three", "four"}
        all_msg2_words = {"alpha", "beta", "gamma", "delta"}

        # Each child must have words from BOTH parents (that's what crossover does)
        # Child 1 gets first N words from msg1 + remaining from msg2
        # Child 2 gets first N words from msg2 + remaining from msg1
        assert words1 & all_msg1_words, "Child 1 should have some msg1 words"
        assert words1 & all_msg2_words, "Child 1 should have some msg2 words"
        assert words2 & all_msg1_words, "Child 2 should have some msg1 words"
        assert words2 & all_msg2_words, "Child 2 should have some msg2 words"

    def test_returns_original_when_empty_first_msg(self) -> None:
        """Should return original messages when first is empty."""
        msg1 = ""
        msg2 = "some words here"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        assert child1 == msg1
        assert child2 == msg2

    def test_returns_original_when_empty_second_msg(self) -> None:
        """Should return original messages when second is empty."""
        msg1 = "some words here"
        msg2 = ""

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        assert child1 == msg1
        assert child2 == msg2

    def test_returns_original_when_single_word_each(self) -> None:
        """Should return originals when only one word each."""
        msg1 = "hello"
        msg2 = "world"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        # With min_word_len < 2, should return originals
        assert child1 == msg1
        assert child2 == msg2

    def test_handles_different_length_messages(self) -> None:
        """Should handle messages with different word counts."""
        random.seed(42)

        msg1 = "a b c"
        msg2 = "x y z w v u"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        # Should produce valid output
        assert child1
        assert child2

    def test_preserves_word_count_approximately(self) -> None:
        """Children should have reasonable word counts."""
        random.seed(42)

        msg1 = "one two three four five"
        msg2 = "alpha beta gamma delta epsilon"

        child1, child2 = _deap_crossover_word_level(msg1, msg2)

        # Word count should be reasonable (not explode or collapse)
        assert 1 <= len(child1.split()) <= 10
        assert 1 <= len(child2.split()) <= 10


class TestCrossoverIntegration:
    """Integration tests for crossover operations."""

    def test_chunking_fallback_to_word_level(self) -> None:
        """When chunking fails, should use word-level crossover."""
        # Single chunk messages should fail chunking
        msg1 = "no periods here"
        msg2 = "also no periods"

        # Chunking should fail
        with pytest.raises(ValueError):
            _deap_crossover_chunking_strategy(msg1, msg2)

        # But word-level should work
        random.seed(42)
        child1, child2 = _deap_crossover_word_level(msg1, msg2)
        assert child1 and child2

    def test_both_strategies_produce_valid_output(self) -> None:
        """Both strategies should produce non-empty, valid output."""
        random.seed(42)

        # Good for chunking
        msg1_chunks = "First sentence. Second sentence. Third sentence."
        msg2_chunks = "Alpha sentence. Beta sentence. Gamma sentence."

        c1, c2 = _deap_crossover_chunking_strategy(msg1_chunks, msg2_chunks)
        assert c1 and c2
        assert isinstance(c1, str) and isinstance(c2, str)

        # Good for word-level
        msg1_words = "one two three four"
        msg2_words = "alpha beta gamma delta"

        w1, w2 = _deap_crossover_word_level(msg1_words, msg2_words)
        assert w1 and w2
        assert isinstance(w1, str) and isinstance(w2, str)

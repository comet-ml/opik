import random
from typing import Any

import pytest

from tests.unit.algorithms.evolutionary_optimizer._crossover_test_helpers import (
    make_deap_individual,
)


class TestDeapCrossover:
    """Tests for deap_crossover function."""

    def test_crossover_dict_individuals(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            deap_crossover,
        )

        random.seed(42)

        ind1 = make_deap_individual(
            {
                "main": [
                    {"role": "system", "content": "First prompt. Second sentence."},
                    {"role": "user", "content": "Question here. Another question."},
                ]
            }
        )
        ind2 = make_deap_individual(
            {
                "main": [
                    {"role": "system", "content": "Alpha prompt. Beta sentence."},
                    {"role": "user", "content": "Query here. Another query."},
                ]
            }
        )

        child1, child2 = deap_crossover(ind1, ind2, verbose=0)

        assert "main" in child1
        assert "main" in child2
        assert len(child1["main"]) == 2
        assert len(child2["main"]) == 2

    def test_crossover_preserves_metadata(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            deap_crossover,
        )

        random.seed(42)

        ind1 = make_deap_individual({"main": [{"role": "system", "content": "Content. More."}]})
        setattr(ind1, "prompts_metadata", {"main": {"name": "main_prompt"}})

        ind2 = make_deap_individual({"main": [{"role": "system", "content": "Alpha. Beta."}]})
        setattr(ind2, "prompts_metadata", {"main": {"name": "main_prompt_v2"}})

        child1, child2 = deap_crossover(ind1, ind2, verbose=0)

        assert hasattr(child1, "prompts_metadata")
        assert hasattr(child2, "prompts_metadata")

    def test_crossover_handles_disjoint_prompts(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            deap_crossover,
        )

        random.seed(42)

        ind1 = make_deap_individual({"prompt_a": [{"role": "system", "content": "A content. More A."}]})
        ind2 = make_deap_individual({"prompt_b": [{"role": "system", "content": "B content. More B."}]})

        child1, child2 = deap_crossover(ind1, ind2, verbose=0)

        assert "prompt_a" in child1
        assert "prompt_b" in child1
        assert "prompt_a" in child2
        assert "prompt_b" in child2


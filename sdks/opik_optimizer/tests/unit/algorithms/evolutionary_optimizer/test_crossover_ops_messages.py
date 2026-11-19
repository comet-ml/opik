import random

from tests.unit.fixtures import system_message, user_message


class TestCrossoverMessages:
    """Tests for _crossover_messages function."""

    def test_crossover_preserves_content_parts(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            _crossover_messages,
        )

        rng = random.Random(42)

        messages1 = [
            user_message(
                [
                    {"type": "text", "text": "First part. Second part."},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/png;base64,abc"},
                    },
                ]
            )
        ]
        messages2 = [
            user_message(
                [
                    {"type": "text", "text": "Alpha part. Beta part."},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/png;base64,xyz"},
                    },
                ]
            )
        ]

        child1_msgs, child2_msgs = _crossover_messages(messages1, messages2, rng)

        assert len(child1_msgs) == 1
        assert isinstance(child1_msgs[0]["content"], list)

    def test_crossover_different_roles_unchanged(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            _crossover_messages,
        )

        rng = random.Random(42)

        messages1 = [system_message("System content. More content.")]
        messages2 = [user_message("User content. More user content.")]

        child1_msgs, child2_msgs = _crossover_messages(messages1, messages2, rng)

        assert len(child1_msgs) == 1
        assert child1_msgs[0]["role"] == "system"

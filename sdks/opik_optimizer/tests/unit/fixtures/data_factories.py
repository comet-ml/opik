"""
Test data factories for opik_optimizer.

This module provides factory classes for generating test data
with sensible defaults and customization options.
"""

from typing import Any
from dataclasses import dataclass, field
from unittest.mock import MagicMock
import random
import string

from opik_optimizer import ChatPrompt


@dataclass
class DatasetItemFactory:
    """
    Factory for generating dataset items with realistic data.

    Usage:
        factory = DatasetItemFactory()

        # Single item
        item = factory.create()

        # Multiple items
        items = factory.create_batch(10)

        # Customized items
        item = factory.create(question="Custom question", answer="Custom answer")

        # With specific fields
        factory = DatasetItemFactory(
            question_template="What is {topic}?",
            answer_template="The answer is {topic}."
        )
    """

    question_template: str = "Question {n}?"
    answer_template: str = "Answer {n}"
    id_prefix: str = "item"
    include_metadata: bool = False
    _counter: int = field(default=0, repr=False)

    def create(
        self,
        *,
        question: str | None = None,
        answer: str | None = None,
        item_id: str | None = None,
        **extra_fields,
    ) -> dict[str, Any]:
        """Create a single dataset item."""
        self._counter += 1

        item: dict[str, Any] = {
            "id": item_id or f"{self.id_prefix}-{self._counter}",
            "question": question or self.question_template.format(n=self._counter),
            "answer": answer or self.answer_template.format(n=self._counter),
        }

        if self.include_metadata:
            item["metadata"] = {"index": self._counter}

        item.update(extra_fields)
        return item

    def create_batch(
        self,
        count: int,
        **shared_fields,
    ) -> list[dict[str, Any]]:
        """Create multiple dataset items."""
        return [self.create(**shared_fields) for _ in range(count)]

    def create_qa_pairs(
        self,
        qa_pairs: list[tuple[str, str]],
    ) -> list[dict[str, Any]]:
        """Create items from explicit question-answer pairs."""
        return [
            self.create(question=q, answer=a)
            for q, a in qa_pairs
        ]

    def reset(self):
        """Reset the counter for reproducible tests."""
        self._counter = 0


@dataclass
class ChatPromptFactory:
    """
    Factory for generating ChatPrompt instances with various configurations.

    Usage:
        factory = ChatPromptFactory()

        # Basic prompt
        prompt = factory.create()

        # With specific content
        prompt = factory.create(
            system="Custom system message",
            user="Custom user message"
        )

        # With tools
        prompt = factory.create_with_tools(["search", "calculator"])

        # With multimodal content
        prompt = factory.create_multimodal()
    """

    default_system: str = "You are a helpful assistant."
    default_user: str = "{question}"
    name_prefix: str = "test-prompt"
    _counter: int = field(default=0, repr=False)

    def create(
        self,
        *,
        name: str | None = None,
        system: str | None = None,
        user: str | None = None,
        messages: list[dict[str, Any]] | None = None,
        tools: list[dict[str, Any]] | None = None,
        model: str | None = None,
        model_kwargs: dict[str, Any] | None = None,
    ) -> ChatPrompt:
        """Create a ChatPrompt with the specified configuration."""
        self._counter += 1

        return ChatPrompt(
            name=name or f"{self.name_prefix}-{self._counter}",
            system=system if messages is None else None,
            user=user if messages is None else None,
            messages=messages,
            tools=tools,
            model=model,
            model_parameters=model_kwargs,
        )

    def create_with_messages(
        self,
        message_count: int = 3,
        *,
        include_system: bool = True,
    ) -> ChatPrompt:
        """Create a prompt with multiple messages."""
        messages = []

        if include_system:
            messages.append({"role": "system", "content": self.default_system})

        for i in range(message_count):
            role = "user" if i % 2 == 0 else "assistant"
            content = f"Message {i + 1}" if role == "user" else f"Response {i + 1}"
            messages.append({"role": role, "content": content})

        return self.create(messages=messages)

    def create_with_tools(
        self,
        tool_names: list[str] | None = None,
    ) -> ChatPrompt:
        """Create a prompt with tool definitions."""
        tool_names = tool_names or ["search"]

        tools = []
        for name in tool_names:
            tools.append({
                "type": "function",
                "function": {
                    "name": name,
                    "description": f"The {name} tool",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {"type": "string", "description": "Input query"}
                        },
                        "required": ["query"],
                    },
                },
            })

        return self.create(
            system=f"Use the following tools: {', '.join(tool_names)}",
            user="{query}",
            tools=tools,
        )

    def create_multimodal(
        self,
        text: str = "What is in this image?",
        image_url: str = "data:image/png;base64,iVBORw0KGgo=",
    ) -> ChatPrompt:
        """Create a prompt with multimodal (image) content."""
        return self.create(
            messages=[
                {"role": "system", "content": "Analyze the image."},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": text},
                        {"type": "image_url", "image_url": {"url": image_url}},
                    ],
                },
            ]
        )

    def reset(self):
        """Reset the counter for reproducible tests."""
        self._counter = 0


@dataclass
class EvaluationResultFactory:
    """
    Factory for creating mock EvaluationResult objects.

    Usage:
        factory = EvaluationResultFactory()

        # Create with specific scores
        result = factory.create(scores=[0.8, 0.6, 0.9])

        # With reasons (required for hierarchical optimizer)
        result = factory.create(
            scores=[0.8, 0.6],
            reasons=["Good answer", "Missing context"]
        )

        # Mixed success/failure
        result = factory.create_mixed(success_rate=0.7, count=10)
    """

    metric_name: str = "accuracy"
    default_reason: str | None = None

    def create(
        self,
        scores: list[float],
        *,
        reasons: list[str] | None = None,
        dataset_item_ids: list[str] | None = None,
        include_failures: bool = False,
    ) -> MagicMock:
        """Create a mock EvaluationResult with the specified scores."""
        mock_result = MagicMock()
        test_results = []

        for i, score in enumerate(scores):
            test_result = MagicMock()

            # Test case
            test_case = MagicMock()
            test_case.dataset_item_id = (
                dataset_item_ids[i] if dataset_item_ids else f"item-{i}"
            )
            test_result.test_case = test_case
            test_result.trial_id = f"trial-{i}"

            # Score result
            score_result = MagicMock()
            score_result.name = self.metric_name
            score_result.value = score
            score_result.reason = (
                reasons[i] if reasons else self.default_reason
            )
            score_result.scoring_failed = include_failures and random.random() < 0.1

            test_result.score_results = [score_result]
            test_results.append(test_result)

        mock_result.test_results = test_results
        return mock_result

    def create_mixed(
        self,
        count: int,
        *,
        success_rate: float = 0.7,
        include_reasons: bool = False,
    ) -> MagicMock:
        """Create results with a mix of high and low scores."""
        scores = []
        reasons = [] if include_reasons else None

        for i in range(count):
            if random.random() < success_rate:
                score = random.uniform(0.7, 1.0)
                reason = "Good response" if include_reasons else None
            else:
                score = random.uniform(0.0, 0.4)
                reason = "Poor response - missing key information" if include_reasons else None

            scores.append(score)
            if reasons is not None and reason is not None:
                reasons.append(reason)

        return self.create(scores, reasons=reasons)

    def create_all_passing(self, count: int) -> MagicMock:
        """Create results where all tests pass (score = 1.0)."""
        return self.create([1.0] * count)

    def create_all_failing(self, count: int) -> MagicMock:
        """Create results where all tests fail (score = 0.0)."""
        return self.create([0.0] * count)


def random_string(length: int = 10) -> str:
    """Generate a random string for testing."""
    return "".join(random.choices(string.ascii_lowercase, k=length))


def random_id() -> str:
    """Generate a random ID string."""
    return f"id-{''.join(random.choices(string.hexdigits.lower(), k=8))}"



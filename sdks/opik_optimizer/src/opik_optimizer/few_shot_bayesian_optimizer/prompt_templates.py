import abc
from typing import Dict, List, Literal, Any
from typing_extensions import override
import opik
from opik.api_objects.prompt import prompt_template as opik_prompt_template

ChatItem = Dict[Literal["role", "content"], str]


class BaseTemplate(abc.ABC):
    @abc.abstractmethod
    def format(self, **kwargs: Any) -> Any:
        raise NotImplementedError

class PromptTemplate(BaseTemplate):
    """Wrapper for opik PromptTemplate which is a subclass of BaseTemplate."""
    def __init__(
        self, 
        template: str,
        validate_placeholders: bool = False,
        type: opik.PromptType = opik.PromptType.MUSTACHE
    ) -> None:
        self._opik_prompt_template = opik_prompt_template.PromptTemplate(
            template=template,
            validate_placeholders=validate_placeholders,
            type=type
        )

    @override
    def format(self, **kwargs: Any) -> str:
        return self._opik_prompt_template.format(**kwargs)


class ChatItemTemplate(BaseTemplate):
    def __init__(
        self, 
        role: str,
        prompt_template: PromptTemplate
    ) -> None:
        self._role = role
        self._prompt_template = prompt_template

    @override
    def format(self, **kwargs: Any) -> ChatItem:
        return {
            "role": self._role,
            "content": self._prompt_template.format(**kwargs)
        }

class ChatPromptTemplate(BaseTemplate):
    def __init__(
        self, 
        chat_template: List[Dict[str, str]],
        type: opik.PromptType = opik.PromptType.MUSTACHE,
        validate_placeholders: bool = False,
    ) -> None:
        self._raw_chat_template = chat_template
        self._type = type
        self._validate_placeholders = validate_placeholders
        self._init_chat_template_items()

    def _init_chat_template_items(self) -> None:
        self._chat_template_items: List[ChatItemTemplate] = [
            ChatItemTemplate(
                role=item["role"],
                prompt_template=PromptTemplate(
                    item["content"],
                    type=self._type,
                    validate_placeholders=self._validate_placeholders,
                )
            )
            for item in self._raw_chat_template
        ]

    @override
    def format(self, **kwargs: Any) -> List[ChatItem]:
        return [
            item.format(**kwargs)
            for item in self._chat_template_items
        ]
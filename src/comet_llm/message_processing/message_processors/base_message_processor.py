import abc
from typing import Optional

from .. import messages
from ... import llm_result

class BaseMessageProcessor(abc.ABC):
    
    @abc.abstractmethod
    def process(message: messages.BaseMessage) -> Optional[llm_result.LLMResult]:
        pass

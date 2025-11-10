from typing import Dict, Any, TYPE_CHECKING
from .base import BasePrompt

if TYPE_CHECKING:
    from .string.prompt import Prompt
    from .chat.chat_prompt import ChatPrompt

def to_info_dict(prompt: BasePrompt) -> Dict[str, Any]:
    # Import here to avoid circular dependencies
    from .string.prompt import Prompt
    from .chat.chat_prompt import ChatPrompt
    
    if isinstance(prompt, Prompt):
        info_dict: Dict[str, Any] = {
            "name": prompt.name,
            "version": {
                "template": prompt.prompt,
            },
        }
    elif isinstance(prompt, ChatPrompt):
        info_dict: Dict[str, Any] = {
            "name": prompt.name,
            "version": {
                "messages": prompt.messages,
            },
        }
    else: 
        raise TypeError(f"Invalid prompt type: {type(prompt)}")

    if prompt.__internal_api__prompt_id__ is not None:
        info_dict["id"] = prompt.__internal_api__prompt_id__

    if prompt.commit is not None:
        info_dict["version"]["commit"] = prompt.commit

    if prompt.__internal_api__version_id__ is not None:
        info_dict["version"]["id"] = prompt.__internal_api__version_id__

    return info_dict

from typing import Any, Dict

from langchain_core.language_models import BaseLLM

__BaseLLM_original_dict = BaseLLM.dict


def base_llm_dict_patch(llm_instance: BaseLLM, **kwargs: Any) -> Dict[str, Any]:
    result = __BaseLLM_original_dict(llm_instance, **kwargs)
    if (
        hasattr(llm_instance, "client")
        and hasattr(llm_instance.client, "_client")
        and hasattr(llm_instance.client._client, "base_url")
    ):
        result["base_url"] = llm_instance.client._client.base_url
    return result

from typing import Any, Dict

from langchain_core import language_models

__BaseLLM_original_dict = language_models.BaseLLM.dict


def base_llm_dict_patch(
    llm_instance: language_models.BaseLLM, **kwargs: Any
) -> Dict[str, Any]:
    result = __BaseLLM_original_dict(llm_instance, **kwargs)
    if (
        hasattr(llm_instance, "client")
        and hasattr(llm_instance.client, "_client")
        and hasattr(llm_instance.client._client, "base_url")
    ):
        result["base_url"] = llm_instance.client._client.base_url
    return result

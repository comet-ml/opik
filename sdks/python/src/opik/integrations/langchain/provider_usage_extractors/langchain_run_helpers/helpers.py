import typing
from typing import Dict, Any, Optional

from . import langchain_usage


class LSMetadata(typing.NamedTuple):
    model: str
    provider: str
    model_type: str


def try_get_token_usage(run_dict: Dict[str, Any]) -> langchain_usage.LangChainUsage:
    if (usage := try_get_streaming_token_usage(run_dict)) is not None:
        return usage

    # try generation_info
    usage_metadata = run_dict["outputs"]["generations"][-1][-1]["generation_info"][
        "usage_metadata"
    ]
    return langchain_usage.LangChainUsage.from_original_usage_dict(usage_metadata)


def try_get_streaming_token_usage(
    run_dict: Dict[str, Any],
) -> Optional[langchain_usage.LangChainUsage]:
    if "message" in run_dict["outputs"]["generations"][-1][-1]:
        usage_metadata = run_dict["outputs"]["generations"][-1][-1]["message"][
            "kwargs"
        ]["usage_metadata"]

        return langchain_usage.LangChainUsage.from_original_usage_dict(usage_metadata)

    return None


def try_get_ls_metadata(run_dict: Dict[str, Any]) -> Optional[LSMetadata]:
    if metadata := run_dict["extra"].get("metadata"):
        model = metadata.get("ls_model_name")
        provider = metadata.get("ls_provider")
        model_type = metadata.get("ls_model_type")
        return LSMetadata(model=model, provider=provider, model_type=model_type)

    return None

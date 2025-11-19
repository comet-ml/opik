from typing import Dict, Any, Optional, Set, NamedTuple, List, Union

from . import langchain_usage


class LSMetadata(NamedTuple):
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
        ].get("usage_metadata")

        if usage_metadata is not None:
            return langchain_usage.LangChainUsage.from_original_usage_dict(
                usage_metadata
            )

    return None


def try_get_ls_metadata(run_dict: Dict[str, Any]) -> Optional[LSMetadata]:
    if metadata := run_dict["extra"].get("metadata"):
        model = metadata.get("ls_model_name")
        provider = metadata.get("ls_provider")
        model_type = metadata.get("ls_model_type")
        return LSMetadata(model=model, provider=provider, model_type=model_type)

    return None


def try_to_get_usage_by_search(
    run_dict: Dict[str, Any], candidate_keys: Optional[Set[str]]
) -> Optional[Union[Dict[str, Any], langchain_usage.LangChainUsage]]:
    """
    Attempts to extract usage data from the given dictionary.

    This function updates the provided set of candidate keys with additional
    keys specific to LangChain usage. It then searches for token usage data
    within the provided dictionary using the updated set of keys. If the usage
    data conforms to the LangChain usage format, it converts it accordingly.
    Otherwise, it simply returns the found usage dictionary if available.

    Args:
        run_dict: Dictionary potentially containing usage data.
        candidate_keys: Set of keys to consider when extracting usage
            information. Additional LangChain-specific keys are added to this set
            or will be used if not provided.

    Returns:
        Extracted usage data. Returns a LangChainUsage object if the data matches
        LangChain usage format. Otherwise, returns a plain dictionary with the usage data,
        or None if no usage data is found.
    """
    if candidate_keys is None:
        all_keys_should_match = True
        candidate_keys = set()
    else:
        all_keys_should_match = False
    candidate_keys.update(langchain_usage.LANGCHAIN_USAGE_KEYS)

    usage_dict = find_token_usage_dict(run_dict, candidate_keys, all_keys_should_match)
    if usage_dict is None:
        return None

    if langchain_usage.is_langchain_usage(usage_dict):
        return langchain_usage.LangChainUsage.from_original_usage_dict(usage_dict)

    return usage_dict


def find_token_usage_dict(
    data: Union[Dict[str, Any], List[Any]],
    candidate_keys: Set[str],
    all_keys_should_match: bool,
) -> Optional[Dict[str, Any]]:
    """
    Find the first dictionary containing any of the specified candidate keys within a nested data structure.

    The function recursively traverses through the given nested data structure, which
    can be a dictionary, list, or tuple. It searches for the first dictionary
    that includes one or more keys from the specified candidate keys and returns it.
    If no such dictionary is found, the function returns None.

    Args:
        all_keys_should_match: if True, all candidate keys must be present in the dictionary.
        data: A nested data structure containing dictionaries, lists, or tuples to search through.
        candidate_keys: A set of strings representing the keys to look for in the dictionaries.

    Returns:
        The first dictionary as a `dict` that contains at least one of the candidate keys.
        Returns None if no such dictionary is found.
    """
    # Handle dictionary case
    if isinstance(data, dict):
        # Check if the current dictionary contains any or all of the candidate keys
        matched_keys = candidate_keys.intersection(data.keys())
        if all_keys_should_match and len(matched_keys) == len(candidate_keys):
            return data
        elif not all_keys_should_match and len(matched_keys) > 0:
            return data

        # Recursively search through dictionary values
        for value in data.values():
            result = find_token_usage_dict(value, candidate_keys, all_keys_should_match)
            if result is not None:
                return result

    # Handle list and tuple cases
    elif isinstance(data, (list, tuple)):
        for item in data:
            result = find_token_usage_dict(item, candidate_keys, all_keys_should_match)
            if result is not None:
                return result

    # If data is neither dict, list, nor tuple, or no token usage found
    return None

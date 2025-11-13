from typing import Dict, Any, Set

import opik.hooks

from .. import jsonable_encoder


def encode_and_anonymize(
    kwargs_dict: Dict[str, Any],
    fields_to_anonymize: Set[str],
) -> Dict[str, Any]:
    """
    Encodes and anonymizes the data in the given dictionary based on the specified
    fields using registered anonymizers. If no anonymizers are registered, the
    function simply encodes the dictionary without anonymization.

    Args:
        kwargs_dict: The dictionary containing the data to encode
            and anonymize.
        fields_to_anonymize: The set of fields within the dictionary to
            anonymize.

    Returns:
        A dictionary that has been encoded and, if applicable, anonymized.
    """
    # check if any anonymizer was registered
    if not opik.hooks.has_anonymizers():
        return jsonable_encoder.encode(kwargs_dict)

    return jsonable_encoder.encode_and_anonymize(
        obj=kwargs_dict,
        anonymizers=opik.hooks.get_anonymizers(),
        fields_to_anonymize=fields_to_anonymize,
    )

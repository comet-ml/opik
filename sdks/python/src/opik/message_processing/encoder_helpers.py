from typing import Dict, Any, Set, List, Literal

import opik.hooks

from .. import jsonable_encoder
from ..anonymizer import anonymizer


def encode_and_anonymize(
    kwargs_dict: Dict[str, Any],
    fields_to_anonymize: Set[str],
    object_type: Literal["span", "trace"],
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
        object_type: A string indicating the type of object ('span' or 'trace')
            that was used to create the kwargs_dict. This is passed to anonymizers
            to provide context about the source object.

    Returns:
        A dictionary that has been encoded and, if applicable, anonymized.
    """
    # check if any anonymizer was registered
    encoded_obj = jsonable_encoder.encode(kwargs_dict)
    if not opik.hooks.has_anonymizers():
        return encoded_obj

    anonymizers = opik.hooks.get_anonymizers()
    return anonymize_encoded_obj(
        obj=encoded_obj,
        fields_to_anonymize=fields_to_anonymize,
        anonymizers=anonymizers,
        object_type=object_type,
    )


def anonymize_encoded_obj(
    obj: Dict[str, Any],
    fields_to_anonymize: Set[str],
    anonymizers: List[anonymizer.Anonymizer],
    object_type: Literal["span", "trace"],
) -> Dict[str, Any]:
    """
    Anonymizes specified fields in an encoded dictionary using the provided anonymizers.
    This function iterates over the given set of field names and applies each anonymizer
    to the corresponding field in the dictionary, if present. The anonymizers are expected
    to implement an `anonymize` method that takes the field value, field name, and object type
    as arguments. Only fields present in the dictionary and listed in `fields_to_anonymize`
    are anonymized.

    Args:
        obj: The encoded dictionary whose fields are to be anonymized.
        fields_to_anonymize: A set of field names within the dictionary to anonymize.
        anonymizers: A list of anonymizer instances to apply to each field.
        object_type: A string indicating the type of object ('span' or 'trace'),
            providing context for anonymization.

    Returns:
        The dictionary with specified fields anonymized using the provided anonymizers.
    """
    if isinstance(obj, dict):
        for field_name in fields_to_anonymize:
            if field_name in obj:
                for anonymizer_instance in anonymizers:
                    obj[field_name] = anonymizer_instance.anonymize(
                        obj[field_name],
                        field_name=field_name,
                        object_type=object_type,
                    )

    return obj

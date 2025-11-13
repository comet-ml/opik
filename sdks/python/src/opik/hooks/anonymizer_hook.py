from typing import List

from opik.anonymizer import anonymizer


# holder for a global list of anonymizers
_anonymizers: List[anonymizer.Anonymizer] = []


def add_anonymizer(anonymizer_hook: anonymizer.Anonymizer) -> None:
    """Register a new anonymizer to be applied to all sensitive data logged by Opik."""
    _anonymizers.append(anonymizer_hook)


def clear_anonymizers() -> None:
    """Clear all registered anonymizers."""
    _anonymizers.clear()


def has_anonymizers() -> bool:
    """Check if any anonymizers have been registered."""
    return len(_anonymizers) > 0


def get_anonymizers() -> List[anonymizer.Anonymizer]:
    """Get a list of all registered anonymizers."""
    return _anonymizers


def apply_anonymizers(
    data: anonymizer.AnonymizerDataType,
) -> anonymizer.AnonymizerDataType:
    """Apply all registered anonymizers to the given data."""
    for anonymizer_ in _anonymizers:
        data = anonymizer_.anonymize(data)
    return data

"""Shared CLI include-option validation used by both export and import commands."""

from typing import Set

import click


def validate_include(
    value: str,
    valid_values: Set[str],
    ctx: click.Context,
    param: click.Parameter,
) -> list:
    """Parse and validate a comma-separated --include option value.

    Raises click.BadParameter when unknown items are present.
    Returns a list of lower-cased, stripped item names.
    """
    parts = [p.strip().lower() for p in value.split(",") if p.strip()]
    invalid = set(parts) - valid_values
    if invalid:
        raise click.BadParameter(
            f"Invalid item(s): {', '.join(sorted(invalid))}. "
            f"Valid values: {', '.join(sorted(valid_values))}."
        )
    return parts

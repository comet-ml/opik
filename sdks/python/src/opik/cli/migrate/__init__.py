"""``opik migrate`` command group.

Slice 1 of the SDK CLI Migration Tool epic. Public surface is the
``migrate_group`` Click command — exported so ``cli/main.py`` can register it.
"""

from .main import migrate_group

__all__ = ["migrate_group"]

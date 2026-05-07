"""Errors raised by ``opik migrate`` planning and execution."""


class MigrationError(Exception):
    """Base class for migration command failures surfaced to the CLI."""


class DatasetNotFoundError(MigrationError):
    """Raised when the source dataset name cannot be resolved."""


class AmbiguityError(MigrationError):
    """Raised when a workspace-scoped name resolves to multiple datasets."""


class ConflictError(MigrationError):
    """Raised when the destination already contains a dataset with the target name."""


class ProjectNotFoundError(MigrationError):
    """Raised when --to-project does not exist.

    Slice 1 deliberately does not auto-create the destination project: a typo
    in --to-project would otherwise silently strand a migration under a brand-
    new project the user did not mean to create.
    """

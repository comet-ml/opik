"""Dataset-specific migration logic for ``opik migrate dataset``.

The top-level ``cli/migrate/`` package keeps generic primitives
(``audit``, ``errors``, ``_base``) and the user-facing Click group
(``main``). Anything dataset-specific lives here so future slices that add
e.g. experiment-cascade or version-history-replay land alongside as their
own sibling sub-packages, not interleaved with the generic code.
"""

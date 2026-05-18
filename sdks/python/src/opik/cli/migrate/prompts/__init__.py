"""Slice 6: ``opik migrate prompt NAME --to-project=B`` implementation.

Mirrors ``cli/migrate/datasets/`` but for the prompt library. The shared
planner/executor/audit infrastructure used here is the same one the
dataset cascade ships on; the helpers ``_create_destination_prompt`` and
``_replay_prompt_versions`` (in ``executor.py`` / ``version_replay.py``)
are designed to be importable from Slice 7's dataset-cascade-prompts
path without modification.
"""

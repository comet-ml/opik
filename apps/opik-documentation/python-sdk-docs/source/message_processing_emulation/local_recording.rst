Local Recording Context Manager
===============================

.. currentmodule:: opik

`record_traces_locally`
-----------------------

The ``record_traces_locally`` context manager enables local, in-memory recording of any traces and spans created inside its block. This is useful for testing, debugging, or for programmatically inspecting your span/trace trees without sending data to the backend.

Basic usage
~~~~~~~~~~~

.. code-block:: python

    import opik

    with opik.record_traces_locally() as storage:
        # Your instrumented code that creates traces/spans
        # e.g., functions decorated with @opik.track, manual opik.Opik().span()/trace(), integrations, etc.
        ...

        # Access in-memory results (automatically flushed before reading)
        span_models = storage.span_trees
        trace_models = storage.trace_trees

What it returns
~~~~~~~~~~~~~~~

The context yields a lightweight handle having these properties:

- ``span_trees``: List of :class:`opik.message_processing.emulation.models.SpanModel`
- ``trace_trees``: List of :class:`opik.message_processing.emulation.models.TraceModel`

Each accessor flushes the Opik client to ensure all in-flight messages are processed before reading the local state.

No nested usage
~~~~~~~~~~~~~~~~

Nested or concurrent usages within the same process are not supported. If a local recording is already active, entering another ``record_traces_locally`` block raises ``RuntimeError``.

Notes
~~~~~

- Uses the SDK's local emulator to mirror what would be sent to the backend.
- Data is kept in memory only for the life of the context. On exit, the local recorder is disabled and state is reset.
- Ideal for `task_span` metrics validation, writing tests or ad-hoc scripts that need access to the span/trace tree structure.



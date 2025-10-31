ExperimentItemModel
===================

.. currentmodule:: opik.message_processing.emulation.models

.. autoclass:: ExperimentItemModel
    :special-members: __init__

Description
-----------

``ExperimentItemModel`` links a trace produced during evaluation to the dataset item
and experiment run that generated it. The SDK instantiates these records for you
while :func:`opik.evaluate` or experiment reruns execute; most users interact with
them through ``ScoreResult.metadata`` rather than constructing instances manually.
Metrics that analyse evaluation outputs can rely on this structure to connect
results back to source data.

Attributes
----------

.. attribute:: id
   :type: str
   :noindex:

   Unique identifier for the experiment item record.

.. attribute:: experiment_id
   :type: str
   :noindex:

   Identifier of the experiment that produced this item.

.. attribute:: trace_id
   :type: str
   :noindex:

   Identifier of the trace logged during the evaluation run.

.. attribute:: dataset_item_id
   :type: str
   :noindex:

   Identifier of the dataset item evaluated in this experiment result.

Usage Example
-------------

The SDK populates ``ExperimentItemModel`` instances automatically while running evaluations:

.. code-block:: python

   from opik.message_processing.emulation.models import ExperimentItemModel

   experiment_item = ExperimentItemModel(
       id="exp_item_001",
       experiment_id="exp_123",
       trace_id="trace_abc",
       dataset_item_id="dataset_item_xyz",
   )

See Also
--------

- :class:`TraceModel` - Stores the trace referenced by ``trace_id``.
- :class:`SpanModel` - Contains spans that reference the same experiment item.
- :doc:`../evaluation/evaluate` - How experiments produce trace results.

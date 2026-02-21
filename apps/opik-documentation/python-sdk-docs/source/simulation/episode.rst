Episode Utilities
=================

.. currentmodule:: opik.simulation

The episode utilities provide a structured result contract for simulation-driven tests.

Core Models
-----------

.. autoclass:: EpisodeResult
   :members:

.. autoclass:: EpisodeAssertion
   :members:

.. autoclass:: EpisodeScore
   :members:

.. autoclass:: EpisodeBudgetMetric
   :members:

.. autoclass:: EpisodeBudgets
   :members:

Helpers
-------

.. autofunction:: build_trajectory_summary

.. autofunction:: make_max_turns_assertion

.. autofunction:: make_tool_call_budget

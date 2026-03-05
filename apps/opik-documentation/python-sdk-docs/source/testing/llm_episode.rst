llm_episode
===========

.. currentmodule:: opik

.. autofunction:: llm_episode

Episode-Based Pytest Simulations
--------------------------------

The ``llm_episode`` decorator extends ``llm_unit`` for multi-turn agent tests. It keeps
the existing pytest experiment linkage and also captures an ``EpisodeResult`` returned by
the test function.

This enables deterministic episode checks in CI, including:

- scenario-level pass/fail assertions
- budget checks (turns, tool calls, latency, token usage)
- lightweight trajectory summaries and rubric scores

Basic Usage
-----------

.. code-block:: python

   from opik import llm_episode
   from opik.simulation import EpisodeResult, EpisodeAssertion

   @llm_episode()
   def test_refund_agent_episode():
       # Run your simulation and assertions here...
       return EpisodeResult(
           scenario_id="refund_flow_v1",
           assertions=[EpisodeAssertion(name="policy_compliance", passed=True)],
       )

Accepted Return Types
---------------------

``llm_episode`` accepts either:

- an ``EpisodeResult`` instance
- a dictionary compatible with ``EpisodeResult``

If ``scenario_id`` is missing, the decorator falls back to the pytest node id.

CI Artifact Output
------------------

When episode capture is enabled, the pytest plugin writes a JSON artifact with episode
status, pytest pass/fail, and full episode payload per test case.

Configuration:

- ``pytest_episode_artifact_enabled`` (default: ``False``)
- ``pytest_episode_artifact_path`` (default: ``".opik/pytest_episode_report.json"``)

You can publish this file in CI/CD so each run keeps a downloadable episode report.
For GitHub Actions:

.. code-block:: yaml

   - name: Run pytest
     run: pytest -q

   - name: Upload episode artifact
     if: always()
     uses: actions/upload-artifact@v4
     with:
       name: pytest-episode-report
       path: .opik/pytest_episode_report.json

Notes
-----

- ``llm_unit`` remains supported as the compatibility layer for test-level tracking.
- ``llm_episode`` is additive and suited for scenario-driven multi-turn tests.

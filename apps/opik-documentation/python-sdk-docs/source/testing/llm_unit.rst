llm_unit
========

.. currentmodule:: opik

.. autofunction:: llm_unit

Description
-----------

The ``llm_unit`` decorator links pytest tests to Opik test experiments. For each decorated
test case, Opik captures test inputs, optional expected output, and the final pass/fail result.

This is useful when you want lightweight test observability in CI without writing a full
evaluation pipeline.

Basic Usage
-----------

.. code-block:: python

   import pytest
   from opik import llm_unit, track

   @track
   def answer(question: str) -> str:
       return "Paris"

   @llm_unit()
   def test_capital():
       assert answer("What is the capital of France?") == "Paris"

Parametrized Tests
------------------

``llm_unit`` works with ``pytest.mark.parametrize`` and stores each parametrized case as a
separate test run.

.. code-block:: python

   import pytest
   from opik import llm_unit

   @llm_unit(expected_output_key="expected_output")
   @pytest.mark.parametrize(
       "question, expected_output",
       [
           ("What is the capital of France?", "Paris"),
           ("What is the capital of Germany?", "Berlin"),
       ],
   )
   def test_capitals(question: str, expected_output: str):
       # Replace with your app call
       output = "Paris" if "France" in question else "Berlin"
       assert output == expected_output

Decorator Arguments
-------------------

``llm_unit`` can map your test argument names to Opik fields:

- ``input_key`` (default: ``"input"``)
- ``expected_output_key`` (default: ``"expected_output"``)
- ``metadata_key`` (default: ``"metadata"``)

If those values are not dictionaries, Opik wraps them automatically.

Configuration
-------------

Use ``pytest_experiment_enabled`` to disable/enable experiment logging for decorated tests.
This setting can be configured via Opik config sources (environment/config file/runtime config).

Additional pytest plugin settings:

- ``pytest_experiment_dataset_name`` (default: ``"tests"``)
- ``pytest_experiment_name_prefix`` (default: ``"Test-Suite"``)
- ``pytest_passed_score_name`` (default: ``"Passed"``)
- ``pytest_episode_artifact_enabled`` (default: ``False``)
- ``pytest_episode_artifact_path`` (default: ``".opik/pytest_episode_report.json"``)

Notes
-----

- The plugin records pass/fail status under the feedback score name ``Passed``.
- A ``tests`` dataset is used for storing linked test-case inputs and expected outputs.
- Async tests are supported when decorated with ``@llm_unit()``.

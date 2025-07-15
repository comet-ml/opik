Automation Rule Evaluators Client
=================================

The Automation Rule Evaluators client provides methods for managing automated evaluation rules in the Opik platform.

.. autoclass:: opik.rest_api.automation_rule_evaluators.client.AutomationRuleEvaluatorsClient
   :members:
   :undoc-members:
   :show-inheritance:
   :inherited-members:
   :exclude-members: with_raw_response

Usage Example
-------------

.. code-block:: python

   import opik
   
   client = opik.Opik()
   
   # List automation rule evaluators
   evaluators = client.rest_client.automation_rule_evaluators.find_automation_rule_evaluators(
       page=0,
       size=10
   )
   
   # Get an evaluator by ID
   evaluator = client.rest_client.automation_rule_evaluators.get_automation_rule_evaluator_by_id(
       "evaluator-id"
   )
   
   # Create a new evaluator
   client.rest_client.automation_rule_evaluators.create_automation_rule_evaluator(
       name="my-evaluator",
       project_id="project-id",
       code="def evaluate(trace): return {'score': 0.8}"
   )
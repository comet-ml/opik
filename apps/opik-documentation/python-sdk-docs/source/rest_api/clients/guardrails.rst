Guardrails Client
=================

The Guardrails client provides methods for managing guardrails in the Opik platform.

.. autoclass:: opik.rest_api.guardrails.client.GuardrailsClient
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
   
   # Validate content with guardrails
   result = client.rest_client.guardrails.validate(
       checks=[
           {
               "name": "pii_check",
               "enabled": True
           },
           {
               "name": "toxicity_check",
               "enabled": True
           }
       ],
       input="This is the text to validate"
   )
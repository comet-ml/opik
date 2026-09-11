Feedback Definitions Client
===========================

The Feedback Definitions client provides methods for managing feedback score definitions in the Opik platform.

.. autoclass:: opik.rest_api.feedback_definitions.client.FeedbackDefinitionsClient
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
   
   # Find feedback definitions
   definitions = client.rest_client.feedback_definitions.find_feedback_definitions(
       type="numerical",
       page=0,
       size=10
   )
   
   # Create a feedback definition
   client.rest_client.feedback_definitions.create_feedback_definition(
       name="accuracy",
       type="numerical",
       min_value=0.0,
       max_value=1.0
   )
   
   # Get a feedback definition by ID
   definition = client.rest_client.feedback_definitions.get_feedback_definition_by_id(
       "definition-id"
   )
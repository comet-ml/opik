LLM Provider Key Client
=======================

The LLM Provider Key client provides methods for managing LLM provider API keys in the Opik platform.

.. autoclass:: opik.rest_api.llm_provider_key.client.LlmProviderKeyClient
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
   
   # Create or update a provider API key
   client.rest_client.llm_provider_key.create_or_update_provider_api_key(
       provider="openai",
       api_key="your-api-key"
   )
   
   # List provider API keys
   keys = client.rest_client.llm_provider_key.get_provider_api_keys(
       page=0,
       size=10
   )
   
   # Delete a provider API key
   client.rest_client.llm_provider_key.delete_provider_api_key(
       provider="openai"
   )
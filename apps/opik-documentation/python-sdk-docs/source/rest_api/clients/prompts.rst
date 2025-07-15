Prompts Client
==============

The Prompts client provides methods for managing prompts in the Opik platform.

.. autoclass:: opik.rest_api.prompts.client.PromptsClient
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
   
   # Create a prompt
   client.rest_client.prompts.create_prompt(
       name="my-prompt",
       prompt="Tell me about {{topic}}",
       type="mustache"
   )
   
   # Get a prompt by name
   prompt = client.rest_client.prompts.get_prompt_by_name("my-prompt")
   
   # List all prompts
   prompts = client.rest_client.prompts.find_prompts(
       page=0,
       size=10
   )
   
   # Get prompt versions
   versions = client.rest_client.prompts.get_prompt_versions(
       prompt_name="my-prompt"
   )
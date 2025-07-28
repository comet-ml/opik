Chat Completions Client
=======================

The Chat Completions client provides methods for interacting with chat completion endpoints in the Opik platform.

.. autoclass:: opik.rest_api.chat_completions.client.ChatCompletionsClient
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
   
   # Create a chat completion
   response = client.rest_client.chat_completions.create(
       model="gpt-3.5-turbo",
       messages=[
           {"role": "user", "content": "Hello, how are you?"}
       ],
       temperature=0.7
   )
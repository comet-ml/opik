Redirect Client
===============

The Redirect client provides methods for handling URL redirects in the Opik platform.

.. autoclass:: opik.rest_api.redirect.client.RedirectClient
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
   
   # Handle redirect operations
   result = client.rest_client.redirect.redirect(
       target_url="https://example.com/target"
   )
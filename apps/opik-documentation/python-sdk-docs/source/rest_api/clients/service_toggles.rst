Service Toggles Client
======================

The Service Toggles client provides methods for managing service feature toggles in the Opik platform.

.. autoclass:: opik.rest_api.service_toggles.client.ServiceTogglesClient
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
   
   # Get service toggles configuration
   config = client.rest_client.service_toggles.get_service_toggles()
   
   # Check if a specific feature is enabled
   if config.feature_enabled:
       # Use the feature
       pass
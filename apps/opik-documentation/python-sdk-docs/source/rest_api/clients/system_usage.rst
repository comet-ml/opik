System Usage Client
===================

The System Usage client provides methods for retrieving system usage metrics in the Opik platform.

.. autoclass:: opik.rest_api.system_usage.client.SystemUsageClient
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
   
   # Get system usage metrics
   usage = client.rest_client.system_usage.get_system_usage()
   
   # Get workspace usage summary
   workspace_usage = client.rest_client.system_usage.get_workspace_usage_summary()
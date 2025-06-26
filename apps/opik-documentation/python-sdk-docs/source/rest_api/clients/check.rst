Check Client
============

The Check client provides methods for checking system status and access in the Opik platform.

.. autoclass:: opik.rest_api.check.client.CheckClient
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
   
   # Check access to the workspace
   client.rest_client.check.access(request={})
   
   # Get workspace name
   workspace_info = client.rest_client.check.get_workspace_name()
   
   # Get bootstrap info
   bootstrap_info = client.rest_client.check.bootstrap()
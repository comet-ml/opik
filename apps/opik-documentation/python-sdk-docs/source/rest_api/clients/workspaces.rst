Workspaces Client
=================

The Workspaces client provides methods for managing workspaces in the Opik platform.

.. autoclass:: opik.rest_api.workspaces.client.WorkspacesClient
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
   
   # Get workspace information
   workspace = client.rest_client.workspaces.get_workspace()
   
   # Get workspace statistics
   stats = client.rest_client.workspaces.get_workspace_stats()
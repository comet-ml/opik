Optimizations Client
====================

The Optimizations client provides methods for managing optimization experiments in the Opik platform.

.. autoclass:: opik.rest_api.optimizations.client.OptimizationsClient
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
   
   # Create an optimization
   client.rest_client.optimizations.create_optimization(
       name="my-optimization",
       dataset_name="my-dataset",
       objective_name="accuracy",
       status="running"
   )
   
   # Get an optimization by ID
   optimization = client.rest_client.optimizations.get_optimization_by_id(
       "optimization-id"
   )
   
   # Update optimization status
   client.rest_client.optimizations.update_optimization(
       id="optimization-id",
       status="completed"
   )
   
   # Delete optimizations
   client.rest_client.optimizations.delete_optimizations_by_id(
       ids=["optimization-id-1", "optimization-id-2"]
   )
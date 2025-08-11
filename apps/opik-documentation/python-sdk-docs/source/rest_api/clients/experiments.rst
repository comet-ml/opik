Experiments Client
==================

The Experiments client provides methods for managing experiments in the Opik platform.

.. autoclass:: opik.rest_api.experiments.client.ExperimentsClient
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
   
   # Find experiments
   experiments = client.rest_client.experiments.find_experiments(
       page=0,
       size=10
   )
   
   # Get an experiment by ID
   experiment = client.rest_client.experiments.get_experiment_by_id("experiment-id")
   
   # Create a new experiment
   client.rest_client.experiments.create_experiment(
       name="my-experiment",
       dataset_name="my-dataset"
   )
   
   # Stream experiment items
   items_generator = client.rest_client.experiments.stream_experiment_items(
       experiment_id="experiment-id"
   )
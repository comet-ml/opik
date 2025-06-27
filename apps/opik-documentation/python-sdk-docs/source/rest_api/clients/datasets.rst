Datasets Client
===============

The Datasets client provides methods for managing datasets in the Opik platform.

.. autoclass:: opik.rest_api.datasets.client.DatasetsClient
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
   
   # Find datasets
   datasets = client.rest_client.datasets.find_datasets(
       page=0,
       size=10
   )
   
   # Get a dataset by ID
   dataset = client.rest_client.datasets.get_dataset_by_id("dataset-id")
   
   # Create a new dataset
   client.rest_client.datasets.create_dataset(
       name="my-dataset",
       description="A test dataset"
   )
   
   # Get dataset items
   items = client.rest_client.datasets.get_dataset_items(
       dataset_id="dataset-id",
       page=0,
       size=100
   )
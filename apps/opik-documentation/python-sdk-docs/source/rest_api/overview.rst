REST API Overview
=================

The Opik SDK provides direct access to the underlying REST API client through the ``rest_client`` property.
This allows advanced users to make direct API calls when needed, providing full access to all Opik platform functionality.

.. warning::
   The REST client is not guaranteed to be backward compatible with future SDK versions.
   While it provides a convenient way to use the current REST API of Opik,
   it's not considered safe to heavily rely on its API as Opik's REST API contracts may change.

When to Use the REST API
------------------------

The REST API is useful when you need to:

* Perform operations not available in the high-level SDK
* Build custom integrations or tools
* Access advanced filtering or querying capabilities
* Implement batch operations for performance
* Work with raw API responses for specific use cases

Getting Started
---------------

To access the REST client, first create an Opik instance and then use the ``rest_client`` property:

.. code-block:: python

   import opik

   # Initialize Opik client
   client = opik.Opik()
   
   # Access REST API through the rest_client property
   rest_client = client.rest_client

Basic Examples
--------------

Here are some common patterns for using the REST API:

**Working with Traces**

.. code-block:: python

   # Get a specific trace
   trace = client.rest_client.traces.get_trace_by_id("trace-id")
   
   # Search for traces with filters
   traces = client.rest_client.traces.search_traces(
       project_name="my-project",
       filters=[{
           "field": "name",
           "operator": "contains",
           "value": "important"
       }],
       max_results=100
   )

**Managing Datasets**

.. code-block:: python

   # List all datasets
   datasets = client.rest_client.datasets.find_datasets(
       page=0,
       size=20
   )
   
   # Create a new dataset
   dataset = client.rest_client.datasets.create_dataset(
       name="my-dataset",
       description="A test dataset"
   )
   
   # Add items to the dataset
   items = [
       {
           "input": {"question": "What is AI?"},
           "expected_output": {"answer": "Artificial Intelligence"}
       }
   ]
   client.rest_client.datasets.create_or_update_dataset_items(
       dataset_id=dataset.id,
       items=items
   )

**Running Experiments**

.. code-block:: python

   # Create an experiment
   experiment = client.rest_client.experiments.create_experiment(
       name="my-experiment",
       dataset_name="my-dataset"
   )
   
   # Add experiment results
   client.rest_client.experiments.create_experiment_items(
       experiment_id=experiment.id,
       items=[{
           "dataset_item_id": "item-id",
           "trace_id": "trace-id",
           "output": {"result": "success"}
       }]
   )

Response Types and Pagination
------------------------------

Most list operations return paginated results with a consistent structure:

.. code-block:: python

   # Example paginated response structure
   response = client.rest_client.datasets.find_datasets(page=0, size=10)
   
   # Access the data
   datasets = response.content  # List of dataset objects
   total_count = response.total  # Total number of items
   current_page = response.page  # Current page number
   page_size = response.size     # Items per page

Error Handling
--------------

The REST API raises specific exceptions for different error conditions:

.. code-block:: python

   from opik.rest_api.core.api_error import ApiError
   
   try:
       trace = client.rest_client.traces.get_trace_by_id("invalid-id")
   except ApiError as e:
       if e.status_code == 404:
           print("Trace not found")
       else:
           print(f"API error: {e.status_code} - {e.body}")

Next Steps
----------

* Browse the :doc:`clients/index` for detailed API reference
* See :doc:`objects` for data type documentation
* Check the main SDK documentation for higher-level operations

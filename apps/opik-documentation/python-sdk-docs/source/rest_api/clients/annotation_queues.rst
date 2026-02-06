Annotation Queues Client
========================

The Annotation Queues client provides methods for managing annotation queues in the Opik platform.
Annotation queues enable human-in-the-loop workflows for reviewing and annotating traces or threads.

.. autoclass:: opik.rest_api.annotation_queues.client.AnnotationQueuesClient
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
   
   # Create an annotation queue for traces
   queue = client.create_annotation_queue(
       name="Review Queue",
       scope="trace",
       description="Queue for reviewing model outputs",
       instructions="Check for accuracy and relevance"
   )
   
   # Get traces and add them to the queue
   traces = client.search_traces(project_name="my-project")
   queue.add_traces(traces[:10])
   
   # Get an existing queue by ID
   existing_queue = client.get_annotation_queue("queue-id")
   
   # List all annotation queues
   queues = client.get_annotation_queues()
   
   # Update queue properties
   queue.update(description="Updated description")
   
   # Remove traces from the queue
   queue.remove_traces(traces[:5])
   
   # Delete the queue
   queue.delete()

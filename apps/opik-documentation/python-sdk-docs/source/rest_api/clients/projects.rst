Projects Client
===============

The Projects client provides methods for managing projects in the Opik platform.

.. autoclass:: opik.rest_api.projects.client.ProjectsClient
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
   
   # Find projects
   projects = client.rest_client.projects.find_projects(
       page=0,
       size=10
   )
   
   # Get a project by ID
   project = client.rest_client.projects.get_project_by_id("project-id")
   
   # Create a new project
   client.rest_client.projects.create_project(
       name="my-project",
       description="A test project"
   )
   
   # Get project metrics
   metrics = client.rest_client.projects.get_project_metrics(
       project_id="project-id",
       metric_type="trace_count",
       interval="1h"
   )
openai
=======

Opik integrates with OpenAI to allow you to log your OpenAI calls to the Opik platform, simply wrap the OpenAI client with `track_openai` to start logging::

   from opik.integrations.openai import track_openai
   from openai import OpenAI

   openai_client = OpenAI()
   openai_client = track_openai(openai_client)

   response = openai_client.Completion.create(
      prompt="Hello, world!",
   )

You can learn more about the `track_openai` decorator in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   track_openai

Anthropic
=========

Opik integrates with Anthropic to allow you to log your Anthropic calls to the Opik platform, simply wrap the Anthropic client with `track_anthropic` to start logging::

    from opik.integrations.anthropic import track_anthropic
    from anthropic import Anthropic

    anthropic_client = Anthropic()
    openai_client = track_openai(openai_client)

    response = anthropic_client.messages.create(
        model="claude-3-5-sonnet-20241022",
        max_tokens=1024,
        messages=[
            {"role": "user", "content": PROMPT}
        ]
    )

You can learn more about the `track_anthropic` decorator in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   track_anthropic

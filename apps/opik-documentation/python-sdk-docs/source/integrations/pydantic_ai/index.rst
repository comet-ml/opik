Pydantic AI
===========

Opik integrates natively with Pydantic AI to log a trace for every ``agent.run``,
with nested spans for each model call and tool call. Simply call ``track_pydantic_ai``
to start logging::

    from pydantic_ai import Agent
    from opik.integrations.pydantic_ai import track_pydantic_ai

    track_pydantic_ai()

    agent = Agent(
        "openai:gpt-4o",
        system_prompt="Be concise, reply with one sentence.",
    )

    result = agent.run_sync('Where does "hello world" come from?')
    print(result.output)


You can learn more about the ``track_pydantic_ai`` function in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   track_pydantic_ai

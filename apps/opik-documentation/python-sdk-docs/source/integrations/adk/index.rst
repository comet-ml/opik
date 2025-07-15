ADK
===

Opik integrates with Adk to allow you to log your ADK agent run to the Opik platform, use the `OpikTracer` callback to start logging::

      from google.adk.agents import Agent
      from opik.integrations.adk import OpikTracer


      opik_tracer = OpikTracer()

      root_agent = Agent(
         name="weather_time_agent",
         model="gemini-2.0-flash-exp",
         description=DESCRIPTION,
         instruction=INSTRUCTION,
         tools=[...],
         before_agent_callback=opik_tracer.before_agent_callback,
         after_agent_callback=opik_tracer.after_agent_callback,
         before_model_callback=opik_tracer.before_model_callback,
         after_model_callback=opik_tracer.after_model_callback,
         before_tool_callback=opik_tracer.before_tool_callback,
         after_tool_callback=opik_tracer.after_tool_callback,
      )

You can learn more about the `OpikTracer` object in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   OpikTracer
   track_adk_agent_recursive

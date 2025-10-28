Simulation
==========

The Opik simulation module provides tools for creating multi-turn conversation simulations between simulated users and your applications. This is particularly useful for evaluating agent behavior over multiple conversation turns.

.. toctree::
   :maxdepth: 1
   
   SimulatedUser
   run_simulation

Overview
--------

Multi-turn simulation allows you to:

- **Simulate realistic user interactions** with your agent over multiple conversation turns
- **Generate context-aware user responses** based on conversation history
- **Evaluate agent behavior** across extended conversations
- **Test different user personas** and scenarios systematically

Key Components
---------------

**SimulatedUser**: A class that generates realistic user responses using LLMs or predefined responses.

**run_simulation**: A function that orchestrates multi-turn conversations between a simulated user and your application.

Basic Usage
-----------

Here's a simple example of how to use the simulation module:

.. code-block:: python

   from opik.simulation import SimulatedUser, run_simulation
   from opik import track

   # Create a simulated user
   user_simulator = SimulatedUser(
       persona="You are a frustrated customer who wants a refund",
       model="openai/gpt-4o-mini"
   )

   # Define your agent
   @track
   def my_agent(user_message: str, *, thread_id: str, **kwargs):
       # Your agent logic here
       return {"role": "assistant", "content": "I can help you with that..."}

   # Run the simulation
   simulation = run_simulation(
       app=my_agent,
       user_simulator=user_simulator,
       max_turns=5
   )

   print(f"Thread ID: {simulation['thread_id']}")
   print(f"Conversation: {simulation['conversation_history']}")

Integration with Evaluation
---------------------------

Simulations work seamlessly with Opik's evaluation framework:

.. code-block:: python

   from opik.evaluation import evaluate_threads
   from opik.evaluation.metrics import ConversationThreadMetric

   # Run multiple simulations
   simulations = []
   for persona in ["frustrated_user", "happy_customer", "confused_user"]:
       simulator = SimulatedUser(persona=f"You are a {persona}")
       simulation = run_simulation(
           app=my_agent,
           user_simulator=simulator,
           max_turns=5
       )
       simulations.append(simulation)

   # Evaluate the threads
   results = evaluate_threads(
       project_name="my_project",
       filter_string='tags contains "simulation"',
       metrics=[ConversationThreadMetric()]
   )

For more detailed examples and advanced usage patterns, see the individual component documentation.

run_simulation
==============

.. currentmodule:: opik.simulation

.. autofunction:: run_simulation

Description
-----------

The ``run_simulation`` function orchestrates multi-turn conversation simulations between a simulated user and your application. It manages the conversation flow, tracks traces, and returns comprehensive results for evaluation.

Key Features
------------

- **Multi-turn conversations**: Runs conversations for a specified number of turns
- **Automatic tracing**: Automatically decorates your app with ``@track`` if not already decorated
- **Thread management**: Groups all traces from a simulation under a single thread ID
- **Error handling**: Gracefully handles errors and continues simulation
- **Flexible configuration**: Supports custom parameters and metadata

Function Signature
------------------

.. code-block:: python

   run_simulation(
       app: Callable,
       user_simulator: SimulatedUser,
       initial_message: Optional[str] = None,
       max_turns: int = 5,
       thread_id: Optional[str] = None,
       project_name: Optional[str] = None,
       tags: Optional[List[str]] = None,
       simulation_state: Optional[Dict[str, Any]] = None,
       **app_kwargs: Any
   ) -> Dict[str, Any]

Parameters
----------

**app** (Callable)
   Your application function that processes messages. Must have signature:
   ``app(message: str, *, thread_id: str, **kwargs) -> Dict[str, str]``
   
   The function will be automatically decorated with ``@track`` if not already decorated.

**user_simulator** (SimulatedUser)
   Instance of ``SimulatedUser`` that generates user responses.

**initial_message** (str, optional)
   Optional initial message from the user. If ``None``, the simulator will generate one.

**max_turns** (int, optional)
   Maximum number of conversation turns. Defaults to 5.

**thread_id** (str, optional)
   Thread ID for grouping traces. If ``None``, a new ID will be generated.

**project_name** (str, optional)
   Project name for trace logging. Included in trace metadata.

**tags** (List[str], optional)
   Optional trace tags applied to all traces produced by the simulation.

**simulation_state** (Dict[str, Any], optional)
   Optional mutable state shared across turns. The object is updated in-place and
   returned in ``simulation["simulation_state"]``.

**app_kwargs** (Any)
   Additional keyword arguments passed to the app function.

Returns
-------

**Dict[str, Any]**
   Dictionary containing:
   
   - **thread_id** (str): The thread ID used for this simulation
   - **conversation_history** (List[Dict[str, str]]): Complete conversation as message dictionaries
   - **project_name** (str, optional): Project name if provided
   - **tags** (List[str], optional): Tags if provided
   - **simulation_state** (Dict[str, Any]): Run-level mutable state with turn metadata

App Function Requirements
-------------------------

Your app function must follow this signature:

.. code-block:: python

   def my_app(user_message: str, *, thread_id: str, **kwargs) -> Dict[str, str]:
       # Process the user message
       # Manage conversation history internally using thread_id
       # Return assistant response as message dict
       return {"role": "assistant", "content": "Your response"}

**Key Requirements:**

1. **First parameter**: Must accept the user message as a string
2. **thread_id parameter**: Must accept thread_id as a keyword-only argument
3. **Return format**: Must return a dictionary with 'role' and 'content' keys
4. **History management**: Your app is responsible for managing conversation history internally

Examples
--------

Basic Usage
~~~~~~~~~~~

.. code-block:: python

   from opik.simulation import SimulatedUser, run_simulation
   from opik import track

   # Create a simulated user
   user_simulator = SimulatedUser(
       persona="You are a customer who wants help with a product",
       model="openai/gpt-4o-mini"
   )

   # Define your agent with conversation history management
   agent_history = {}

   @track
   def customer_service_agent(user_message: str, *, thread_id: str, **kwargs):
       if thread_id not in agent_history:
           agent_history[thread_id] = []
       
       # Add user message to history
       agent_history[thread_id].append({"role": "user", "content": user_message})
       
       # Process with full conversation context
       messages = agent_history[thread_id]
       
       # Your agent logic here (e.g., call LLM)
       response = "I can help you with that. What specific issue are you experiencing?"
       
       # Add assistant response to history
       agent_history[thread_id].append({"role": "assistant", "content": response})
       
       return {"role": "assistant", "content": response}

   # Run the simulation
   simulation = run_simulation(
       app=customer_service_agent,
       user_simulator=user_simulator,
       max_turns=5,
       project_name="customer_service_evaluation"
   )

   print(f"Thread ID: {simulation['thread_id']}")
   print(f"Conversation length: {len(simulation['conversation_history'])}")

Custom Initial Message
~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   # Start with a specific initial message
   simulation = run_simulation(
       app=customer_service_agent,
       user_simulator=user_simulator,
       initial_message="I'm having trouble with my order",
       max_turns=3
   )

Custom Thread ID
~~~~~~~~~~~~~~~~

.. code-block:: python

   # Use a custom thread ID for easier tracking
   custom_thread_id = "simulation_test_001"
   
   simulation = run_simulation(
       app=customer_service_agent,
       user_simulator=user_simulator,
       thread_id=custom_thread_id,
       max_turns=5
   )

Multiple Simulations
~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   # Run multiple simulations with different personas
   personas = [
       "You are a frustrated customer who wants a refund",
       "You are a happy customer who wants to buy more",
       "You are a confused user who needs help with setup"
   ]

   simulations = []
   for i, persona in enumerate(personas):
       simulator = SimulatedUser(persona=persona)
       simulation = run_simulation(
           app=customer_service_agent,
           user_simulator=simulator,
           max_turns=5,
           project_name="multi_persona_evaluation"
       )
       simulations.append(simulation)
       print(f"Simulation {i+1} completed: {simulation['thread_id']}")

Integration with Evaluation
~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   from opik.evaluation import evaluate_threads
   from opik.evaluation.metrics import ConversationThreadMetric

   # Run simulations
   simulation = run_simulation(
       app=customer_service_agent,
       user_simulator=user_simulator,
       max_turns=5,
       project_name="evaluation_test"
   )

   # Evaluate the simulation thread
   results = evaluate_threads(
       project_name="evaluation_test",
       filter_string=f'thread_id = "{simulation["thread_id"]}"',
       metrics=[ConversationThreadMetric()]
   )

Advanced Usage with Tags and Custom App Parameters
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   # Pass additional app kwargs for scenario-level behavior.
   # These parameters are available inside your app callable.
   simulation = run_simulation(
       app=customer_service_agent,
       user_simulator=user_simulator,
       max_turns=5,
       project_name="tagged_simulation",
       tags=["simulation", "customer_service"],  # Trace tags
       simulation_id="test_001",  # Custom parameter
       scenario_label="customer_service"  # Custom parameter
   )

   # Your app can access these parameters
   @track
   def tagged_agent(user_message: str, *, thread_id: str, simulation_id: str = None, scenario_label: str = None, **kwargs):
       # Use custom parameters for scenario-specific logic
       if simulation_id:
           print(f"Running simulation: {simulation_id}")
       
       return {"role": "assistant", "content": "Response"}

Stateful Simulation Pattern
~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   from opik import track
   from opik.simulation import run_simulation, SimulatedUser

   @track
   def stateful_agent(
       user_message: str,
       *,
       thread_id: str,
       simulation_state: dict | None = None,
       **kwargs
   ):
       # run_simulation keeps this object across turns
       turns = int(simulation_state.get("agent_turns", 0)) + 1
       simulation_state["agent_turns"] = turns
       simulation_state["last_intent"] = "billing" if "invoice" in user_message.lower() else "support"

       return {"role": "assistant", "content": f"Handled turn {turns}"}

   shared_state = {"scenario_id": "billing_recovery_v1"}
   simulation = run_simulation(
       app=stateful_agent,
       user_simulator=SimulatedUser(
           persona="You ask account and billing follow-ups",
           fixed_responses=["I need account help", "What is your invoice dispute policy?"],
       ),
       max_turns=2,
       simulation_state=shared_state,
   )

   # The same object is updated in-place and returned
   assert simulation["simulation_state"]["agent_turns"] == 2

Error Handling
~~~~~~~~~~~~~~

.. code-block:: python

   @track
   def error_prone_agent(user_message: str, *, thread_id: str, **kwargs):
       # This might raise an exception
       if "error" in user_message.lower():
           raise ValueError("Simulated error")
       
       return {"role": "assistant", "content": "Normal response"}

   # run_simulation handles errors gracefully
   simulation = run_simulation(
       app=error_prone_agent,
       user_simulator=user_simulator,
       max_turns=3
   )

   # Errors are captured in the conversation history
   for message in simulation['conversation_history']:
       if "Error processing message" in message.get('content', ''):
           print(f"Error occurred: {message['content']}")

Best Practices
--------------

1. **Thread Management**: Always use the provided ``thread_id`` to manage conversation history
2. **Error Handling**: Implement proper error handling in your app function
3. **Return Format**: Always return message dictionaries with 'role' and 'content' keys
4. **History Management**: Keep conversation history in a thread-safe way if running concurrent simulations
5. **Resource Management**: Be mindful of token usage with long conversations
6. **Testing**: Use fixed responses in SimulatedUser for deterministic testing

Notes
-----

- The function automatically decorates your app with ``@track`` if not already decorated
- All traces from a simulation are grouped under the same thread ID
- The function handles errors gracefully and continues the simulation
- Conversation history is returned as a list of message dictionaries
- Custom parameters passed via ``**app_kwargs`` are forwarded to your app function

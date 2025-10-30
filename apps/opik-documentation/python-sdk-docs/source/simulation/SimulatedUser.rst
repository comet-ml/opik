SimulatedUser
=============

.. currentmodule:: opik.simulation

.. autoclass:: SimulatedUser
   :members:
   :special-members: __init__

Description
-----------

The ``SimulatedUser`` class generates realistic user responses for multi-turn conversation simulations. It can use either LLM-generated responses or predefined fixed responses, making it flexible for different testing scenarios.

Key Features
------------

- **LLM-powered responses**: Uses any supported LLM model to generate context-aware user responses
- **Fixed responses**: Option to use predefined responses for deterministic testing
- **Persona-based behavior**: Simulates different user personalities and behaviors
- **Conversation context**: Generates responses based on full conversation history

Constructor
-----------

.. code-block:: python

   SimulatedUser(
       persona: str,
       model: str = "gpt-4o-mini",
       fixed_responses: Optional[List[str]] = None
   )

Parameters
----------

**persona** (str)
   Description of the user's personality and behavior. This is used as a system prompt to guide the LLM's response generation.

**model** (str, optional)
   LLM model to use for generating responses. Defaults to "gpt-4o-mini". Supports any model available through Opik's model factory.

**fixed_responses** (List[str], optional)
   List of predefined responses to cycle through. If provided, these responses will be used instead of LLM generation.

Methods
-------

generate_response
~~~~~~~~~~~~~~~~

.. code-block:: python

   generate_response(conversation_history: List[Dict[str, str]]) -> str

Generates a response based on the conversation history.

**Parameters:**

- **conversation_history** (List[Dict[str, str]]): List of message dictionaries with 'role' and 'content' keys

**Returns:**

- **str**: String response from the simulated user

**Behavior:**

- If ``fixed_responses`` are provided, cycles through them in order
- Otherwise, uses the LLM to generate context-aware responses based on the persona and conversation history
- Automatically limits conversation history to last 10 messages to avoid token limits

Examples
--------

Basic Usage
~~~~~~~~~~~

.. code-block:: python

   from opik.simulation import SimulatedUser

   # Create a simulated user with a specific persona
   user_simulator = SimulatedUser(
       persona="You are a frustrated customer who wants a refund for a broken product",
       model="openai/gpt-4o-mini"
   )

   # Generate a response based on conversation history
   conversation = [
       {"role": "assistant", "content": "Hello, how can I help you today?"},
       {"role": "user", "content": "My product broke after 2 days, I want a refund."},
       {"role": "assistant", "content": "I'm sorry to hear that. What happened?"}
   ]

   response = user_simulator.generate_response(conversation)
   print(response)  # Output: "It just stopped working! I've barely used it..."

Fixed Responses
~~~~~~~~~~~~~~~

.. code-block:: python

   # Use predefined responses for deterministic testing
   user_simulator = SimulatedUser(
       persona="Test user",
       fixed_responses=[
           "I want a refund",
           "This is taking too long",
           "Can I speak to a manager?",
           "I'm not satisfied with this service"
       ]
   )

   # Responses will cycle through the fixed list
   response1 = user_simulator.generate_response([])  # "I want a refund"
   response2 = user_simulator.generate_response([])   # "This is taking too long"
   response3 = user_simulator.generate_response([])   # "Can I speak to a manager?"

Different Personas
~~~~~~~~~~~~~~~~~~

.. code-block:: python

   # Happy customer persona
   happy_customer = SimulatedUser(
       persona="You are a satisfied customer who loves the product and wants to buy more",
       model="openai/gpt-4o-mini"
   )

   # Confused user persona
   confused_user = SimulatedUser(
       persona="You are a confused user who needs help understanding how to use the product",
       model="openai/gpt-4o-mini"
   )

   # Technical user persona
   technical_user = SimulatedUser(
       persona="You are a technical user who asks detailed questions about implementation and integration",
       model="openai/gpt-4o-mini"
   )

Integration with run_simulation
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   from opik.simulation import SimulatedUser, run_simulation
   from opik import track

   @track
   def customer_service_agent(user_message: str, *, thread_id: str, **kwargs):
       # Your agent logic here
       return {"role": "assistant", "content": "I understand your concern..."}

   # Create multiple user personas for testing
   personas = [
       "You are a frustrated customer who wants a refund",
       "You are a happy customer who wants to buy more products",
       "You are a confused user who needs help with setup"
   ]

   for i, persona in enumerate(personas):
       simulator = SimulatedUser(persona=persona)
       simulation = run_simulation(
           app=customer_service_agent,
           user_simulator=simulator,
           max_turns=5,
           project_name="customer_service_evaluation"
       )
       print(f"Simulation {i+1} completed: {simulation['thread_id']}")

Best Practices
--------------

1. **Clear Personas**: Write detailed, specific personas to get consistent behavior
2. **Model Selection**: Choose appropriate models based on your needs (faster models for testing, more capable models for realistic simulation)
3. **Fixed Responses**: Use fixed responses for deterministic testing scenarios
4. **Context Management**: The class automatically handles conversation context, but be aware of token limits
5. **Error Handling**: The class includes fallback responses if LLM generation fails

Notes
-----

- The class uses Opik's model factory for LLM integration, ensuring consistency with other Opik features
- Responses are generated as strings, not message dictionaries
- The persona is used as a system prompt to guide response generation
- Fixed responses cycle through the list in order, starting over when exhausted

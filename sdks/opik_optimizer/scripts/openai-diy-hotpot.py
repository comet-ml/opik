import json
from typing import Optional, Dict, Any, List
from openai import OpenAI
import os

# Tools:
import dspy


class WikipediaAgent:
    def __init__(self):
        self.client = OpenAI(api_key=os.getenv('OPENAI_API_KEY'))
        self.conversation_history = []

    def search_wikipedia(self, query: str) -> List[str]:
        """
        This agent is used to search wikipedia. It can retrieve additional details
        about a topic.
        """
        print("Lookup: %r" % query)
        results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
            query, k=3
        )
        #print("Result:")
        #print(results[0]["text"])
        return [item["text"] for item in results]

        
    def create_tool_definitions(self) -> List[Dict[str, Any]]:
        """Creates OpenAI function calling definitions for the tools."""
        return [
            {
                "type": "function",
                "function": {
                    "name": "search_wikipedia",
                    "description": "This agent is used to search wikipedia. It can retrieve additional details about a topic.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "The information to look up in wikipedia"
                            }
                        },
                        "required": ["query"]
                    }
                }
            },
        ]
    
    def execute_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Any:
        """Executes the specified tool with given arguments."""
        if tool_name == "search_wikipedia":
            return self.search_wikipedia(arguments["query"])
        else:
            return None

    def invoke(self, user_query):
        self.conversation_history = []
        return self.process_user_query(user_query)
    
    def process_user_query(self, user_query: str) -> str:
        """Processes a user query using the OpenAI API with function calling."""
        # Add user message to conversation history
        self.conversation_history.append({"role": "user", "content": user_query})
        
        tool_use_count = 0
        while tool_use_count < 6:
            messages = [
                {"role": "system", "content": self.system_prompt},
                *self.conversation_history
            ]
            
            # Call OpenAI API with function calling
            response = self.client.chat.completions.create(
                model="gpt-4-turbo-preview",
                messages=messages,
                tools=self.create_tool_definitions(),
                tool_choice="auto"
            )
            
            response_message = response.choices[0].message
            
            # If no tool calls, we're done
            print("response_message.tool_calls:", response_message.tool_calls)
            if not response_message.tool_calls:
                self.conversation_history.append({"role": "assistant", "content": response_message.content})
                return response_message.content
            
            # Execute the first tool call
            tool_call = response_message.tool_calls[0]
            function_name = tool_call.function.name
            function_args = json.loads(tool_call.function.arguments)
            
            print(f"\nExecuting tool: {function_name} with args: {function_args}")
            
            # Execute the tool
            tool_use_count += 1
            result = self.execute_tool(function_name, function_args)
            
            # Add the assistant's message with tool calls to history
            self.conversation_history.append({
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": tool_call.id,
                    "type": "function",
                    "function": {
                        "name": function_name,
                        "arguments": json.dumps(function_args)
                    }
                }]
            })
            
            # Add tool result to history
            self.conversation_history.append({
                "tool_call_id": tool_call.id,
                "role": "tool",
                "name": function_name,
                "content": str(result) if result is not None else "No result found"
            })
        else:
            return "Unable to answer the question."
    
    def chat(self):
        """Interactive chat loop."""
        print("Wikipedia Agent")
        print("Ask me anything!")
        print("Type 'quit' to exit.\n")
        
        while True:
            user_input = input("You: ")
            
            if user_input.lower() in ['quit', 'exit', 'bye']:
                print("Goodbye!")
                break
            
            try:
                response = self.process_user_query(user_input)
                print(f"\nAgent: {response}\n")
            except Exception as e:
                print(f"\nError: {e}\n")

from opik_optimizer.demo import get_or_create_dataset
from opik.integrations.langchain import OpikTracer
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import (
    TaskConfig,
    MetricConfig,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.agent_optimizer import OpikAgentOptimizer, OpikAgent

project_name = "openai-dyi-hotpot"

dataset = get_or_create_dataset("hotpot-300")

system_prompt = """You are a helpful assistant. You have access to a tool that can
lookup information on a topic in wikipedia. 
Use this tool to help answer questions that arise, and then use the results of the tool to 
answer the user's question with a short, concise word or phrase.
Compare the results of using the tool before answering.
"""
agent_config = {
    "chat-prompt": {"type": "chat", "value": []},
    "Wikipedia Search": {
        "type": "tool",
        "value": "Search wikipedia for abstracts. Gives a brief paragraph about a topic.",
        "function": "search_wikipedia",
    },
    "system-prompt": {"type": "prompt", "value": system_prompt, "template": False},
}

class OpenAIDIYAgent(OpikAgent):
    agent = WikipediaAgent()

    def reconfig(self, agent_config):
        self.agent.system_prompt = agent_config["system-prompt"]["value"]

    def invoke(self, item: Dict[str, Any], input_dataset_field: str) -> Dict[str, Any]:
        result = self.agent.invoke(item[input_dataset_field])
        return {"output": result}

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

task_config = TaskConfig(
    instruction_prompt=system_prompt,
    input_dataset_fields=["question"],
    output_dataset_field="answer",
)

agent = OpenAIDIYAgent(agent_config, project_name)

result = agent.invoke(
    {"question": "Which is heavier: a newborn elephant, or a motor boat?"},
    "question",
)
print(result)

metaprompt = """Refine this prompt to make it better. Just give me the better prompt, nothing else.

Suggest things like keeping the answer brief. The answers should be like answers
to a trivia question.

Here is the prompt:

%r
"""

optimizer = OpikAgentOptimizer(
    task_config=task_config,
)

optimizer.optimize_agent(
    agent=agent,
    dataset=dataset,
    metric_config=metric_config,
    n_samples=10,
    num_threads=1,
    metaprompt=metaprompt,
)

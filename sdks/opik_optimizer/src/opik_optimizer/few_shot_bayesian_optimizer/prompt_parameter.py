import pydantic
from typing import List, Dict, Literal
import json

from . import prompt_templates

ChatItem = Dict[Literal["role", "content"], str]


class ChatPromptParameter(pydantic.BaseModel):
    name: str
    instruction: str
    task_input_parameters: List[str]
    task_output_parameter: str
    demo_examples: List[Dict[str, str]] = pydantic.Field(default_factory=list)

    _few_shot_system_prompt_intro: str = "You are an intelligent assistant that learns from few-shot examples provided earlier in the conversation. Whenever you respond, carefully follow the structure, tone, and format of previous assistant replies, using them as a guide"
    
    def as_template(self) -> prompt_templates.ChatPromptTemplate:
        if not self.demo_examples:
            return prompt_templates.ChatPromptTemplate(
                chat_template=[
                    {
                        "role": "system",
                        "content": self.instruction
                    },
                    {
                        "role": "user",
                        "content": json.dumps({param: f"{{{{{param}}}}}" for param in self.task_input_parameters})
                    }
                ]
            )

        return prompt_templates.ChatPromptTemplate(
            chat_template=[
                {
                    "role": "system",
                    "content": self.instruction + f"\n\n{self._few_shot_system_prompt_intro}"
                },
                *self._render_demos(),
                {
                    "role": "user",
                    "content": json.dumps({param: f"{{{{{param}}}}}" for param in self.task_input_parameters})
                }
            ]
        )
    
    def _render_demos(self) -> List[ChatItem]:
        """
        Renders demo examples in the following format:

        [
            {
                "role": "user",
                "content": "\n{\n\"input_field1\": \"value1\",\n\"input_field2\": \"value2\"\n}\n"
            },
            {
                "role": "assistant",
                "content": "expected_response_1"
            },
            {
                "role": "user",
                "content": "\n{\n\"input_field1\": \"value3\",\n\"input_field2\": \"value4\"\n}\n"
            },
            {
                "role": "assistant",
                "content": "expected_response_2"
            }
        ]
        """
        chat_items: List[ChatItem] = []
        
        for example in self.demo_examples:
            inputs = {param: example[param] for param in self.task_input_parameters}
            
            formatted_input = json.dumps(inputs, indent=2)
            
            user_message: ChatItem = {
                "role": "user",
                "content": f"\n{formatted_input}\n"
            }
            
            assistant_message: ChatItem = {
                "role": "assistant",
                "content": example[self.task_output_parameter]
            }

            chat_items.append(user_message)
            chat_items.append(assistant_message)
            
        return chat_items

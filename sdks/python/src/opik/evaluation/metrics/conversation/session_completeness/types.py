from typing import Dict, Literal, List

ConversationDict = Dict[Literal["role", "content"], str]
Conversation = List[ConversationDict]

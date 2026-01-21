from opik_optimizer.api_objects.chat_prompt import ChatPrompt


class TestChatPromptWithTools:
    """Tests for ChatPrompt with tools."""

    def test_stores_tools(self) -> None:
        """Should store tools when provided."""
        tools = [
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "Search the web",
                    "parameters": {
                        "type": "object",
                        "properties": {"query": {"type": "string"}},
                        "required": ["query"],
                    },
                },
            }
        ]
        prompt = ChatPrompt(system="Use tools", tools=tools)

        assert prompt.tools == tools

    def test_wraps_function_map_with_track(self) -> None:
        """Should wrap function_map functions with Opik track."""

        def my_tool(query: str) -> str:
            return f"Result for {query}"

        prompt = ChatPrompt(system="Test", function_map={"my_tool": my_tool})

        assert "my_tool" in prompt.function_map

    def test_preserves_already_tracked_functions(self) -> None:
        """Should preserve functions that are already tracked."""
        from opik import track

        @track(type="tool")
        def my_tracked_tool(query: str) -> str:
            return f"Result for {query}"

        prompt = ChatPrompt(system="Test", function_map={"my_tool": my_tracked_tool})

        assert prompt.function_map["my_tool"] is my_tracked_tool

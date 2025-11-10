from opik.api_objects.prompt import ChatPromptTemplate, PromptType


def test_chat_prompt_template__format__simple_text_message__happyflow():
    """Test basic formatting of a simple text message."""
    messages = [
        {
            "role": "user",
            "content": "Hi, my name is {{name}} and I live in {{city}}.",
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format({"name": "Harry", "city": "London"})
    
    assert result == [
        {"role": "user", "content": "Hi, my name is Harry and I live in London."}
    ]


def test_chat_prompt_template__format__multiple_messages__happyflow():
    """Test formatting multiple messages with different roles."""
    messages = [
        {"role": "system", "content": "You are a helpful assistant in {{location}}."},
        {"role": "user", "content": "What is the capital of {{country}}?"},
        {"role": "assistant", "content": "The capital is {{capital}}."},
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format({
        "location": "London",
        "country": "France",
        "capital": "Paris",
    })
    
    assert result == [
        {"role": "system", "content": "You are a helpful assistant in London."},
        {"role": "user", "content": "What is the capital of France?"},
        {"role": "assistant", "content": "The capital is Paris."},
    ]


def test_chat_prompt_template__format__multimodal_content__happyflow():
    """Test formatting messages with multimodal content (text + image) when vision is supported."""
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Describe this {{object}}:"},
                {"type": "image_url", "image_url": {"url": "{{image_url}}"}},
            ],
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    # Format with vision supported to get structured content
    result = tested.format(
        {"object": "painting", "image_url": "https://example.com/image.jpg"},
        supported_modalities={"vision": True}
    )
    
    assert result == [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Describe this painting:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}},
            ],
        }
    ]


def test_chat_prompt_template__format__jinja2_template__happyflow():
    """Test formatting with Jinja2 template type."""
    messages = [
        {
            "role": "user",
            "content": "Hi, my name is {{ name }} and I live in {{ city }}.",
        }
    ]
    
    tested = ChatPromptTemplate(messages, template_type=PromptType.JINJA2)
    
    result = tested.format({"name": "Harry", "city": "London"})
    
    assert result == [
        {"role": "user", "content": "Hi, my name is Harry and I live in London."}
    ]


def test_chat_prompt_template__format__jinja2_with_control_flow():
    """Test Jinja2 formatting with control flow."""
    messages = [
        {
            "role": "user",
            "content": """
            {% if is_wizard %}
            {{ name }} is a wizard who lives in {{ city }}.
            {% else %}
            {{ name }} is a muggle who lives in {{ city }}.
            {% endif %}
            """,
        }
    ]
    
    tested = ChatPromptTemplate(messages, template_type=PromptType.JINJA2)
    
    wizard_result = tested.format({"name": "Harry", "city": "London", "is_wizard": True})
    assert "Harry is a wizard who lives in London." in wizard_result[0]["content"].strip()
    
    muggle_result = tested.format({"name": "Dudley", "city": "Surrey", "is_wizard": False})
    assert "Dudley is a muggle who lives in Surrey." in muggle_result[0]["content"].strip()


def test_chat_prompt_template__format__jinja2_with_loops():
    """Test Jinja2 formatting with loops."""
    messages = [
        {
            "role": "user",
            "content": """
            {{ name }}'s friends are:
            {% for friend in friends %}
            - {{ friend }}
            {% endfor %}
            """,
        }
    ]
    
    tested = ChatPromptTemplate(messages, template_type=PromptType.JINJA2)
    
    result = tested.format({"name": "Harry", "friends": ["Ron", "Hermione", "Neville"]})
    
    content = result[0]["content"]
    assert "Harry's friends are:" in content
    assert "- Ron" in content
    assert "- Hermione" in content
    assert "- Neville" in content


def test_chat_prompt_template__format__empty_content():
    """Test formatting with empty content."""
    messages = [
        {"role": "user", "content": ""},
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format({"name": "Harry"})
    
    assert result == [{"role": "user", "content": ""}]


def test_chat_prompt_template__format__message_without_role__skipped():
    """Test that messages without a role are skipped."""
    messages = [
        {"role": "user", "content": "Hello {{name}}"},
        {"content": "This message has no role"},
        {"role": "assistant", "content": "Hi there!"},
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format({"name": "Harry"})
    
    # Only messages with roles should be included
    assert result == [
        {"role": "user", "content": "Hello Harry"},
        {"role": "assistant", "content": "Hi there!"},
    ]


def test_chat_prompt_template__required_modalities__text_only():
    """Test required_modalities returns empty set for text-only messages."""
    messages = [
        {"role": "user", "content": "Simple text message"},
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.required_modalities()
    
    assert result == set()


def test_chat_prompt_template__required_modalities__with_vision():
    """Test required_modalities detects vision modality."""
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Describe this image:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
            ],
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.required_modalities()
    
    assert "vision" in result


def test_chat_prompt_template__required_modalities__multiple_messages():
    """Test required_modalities across multiple messages."""
    messages = [
        {"role": "user", "content": "Text only"},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "With image:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
            ],
        },
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.required_modalities()
    
    assert "vision" in result


def test_chat_prompt_template__format__unsupported_modality_replaced_with_placeholder():
    """Test that unsupported modalities are replaced with placeholders."""
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Describe this image:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
            ],
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    # Format with vision not supported
    result = tested.format({}, supported_modalities={"vision": False})
    
    assert len(result) == 1
    # When vision is not supported, image should be replaced with placeholder
    content = result[0]["content"]
    assert isinstance(content, str)
    assert "<<<image>>>" in content
    assert "<<</image>>>" in content
    assert "Describe this image:" in content


def test_chat_prompt_template__format__supported_modality_preserved():
    """Test that supported modalities are preserved as structured content."""
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Describe this image:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
            ],
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    # Format with vision supported
    result = tested.format({}, supported_modalities={"vision": True})
    
    assert result == [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Describe this image:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
            ],
        }
    ]


def test_chat_prompt_template__format__multimodal_with_template_variables():
    """Test multimodal content with template variables in both text and image URL."""
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Analyze this {{object}}:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/{{filename}}"}},
            ],
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format(
        {"object": "diagram", "filename": "diagram.png"},
        supported_modalities={"vision": True}
    )
    
    assert result == [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Analyze this diagram:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/diagram.png"}},
            ],
        }
    ]


def test_chat_prompt_template__format__image_with_detail_parameter():
    """Test that image detail parameter is preserved during formatting."""
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Analyze:"},
                {
                    "type": "image_url",
                    "image_url": {"url": "{{url}}", "detail": "high"},
                },
            ],
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format(
        {"url": "https://example.com/img.jpg"},
        supported_modalities={"vision": True}
    )
    
    assert result == [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Analyze:"},
                {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg", "detail": "high"}},
            ],
        }
    ]


def test_chat_prompt_template__messages_property():
    """Test that messages property returns the original messages."""
    messages = [
        {"role": "user", "content": "Hello {{name}}"},
        {"role": "assistant", "content": "Hi there!"},
    ]
    
    tested = ChatPromptTemplate(messages)
    
    assert tested.messages == messages


def test_chat_prompt_template__format__override_template_type():
    """Test that template_type can be overridden in format call."""
    messages = [
        {"role": "user", "content": "Name: {{name}}, City: {{city}}"},
    ]
    
    # Create with Mustache default
    tested = ChatPromptTemplate(messages, template_type=PromptType.MUSTACHE)
    
    # Override with Jinja2 in format
    result = tested.format(
        {"name": "Harry", "city": "London"},
        template_type=PromptType.JINJA2,
    )
    
    assert result == [
        {"role": "user", "content": "Name: Harry, City: London"}
    ]


def test_chat_prompt_template__format__one_placeholder_used_multiple_times():
    """Test formatting with the same placeholder used multiple times."""
    messages = [
        {
            "role": "user",
            "content": "My name is {{name}}. I repeat, my name is {{name}}.",
        }
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format({"name": "Harry"})
    
    assert result == [
        {"role": "user", "content": "My name is Harry. I repeat, my name is Harry."}
    ]


def test_chat_prompt_template__format__empty_messages_list():
    """Test formatting with empty messages list."""
    messages = []
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format({"name": "Harry"})
    
    assert result == []


def test_chat_prompt_template__format__message_with_missing_content():
    """Test formatting when message has no content field."""
    messages = [
        {"role": "user"},  # No content field
    ]
    
    tested = ChatPromptTemplate(messages)
    
    result = tested.format({"name": "Harry"})
    
    assert result == [{"role": "user", "content": ""}]


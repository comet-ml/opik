#!/usr/bin/env python3
"""
Test script to verify Bedrock cost tracking with local Opik instance.
"""

import os
import sys
import time
from datetime import datetime

# Add the SDK to the path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'sdks', 'python', 'src'))

from opik import track, opik_context

def test_bedrock_cost_tracking():
    """Test Bedrock cost tracking functionality."""
    
    print("🧪 Testing Bedrock Cost Tracking with Local Opik Instance")
    print("=" * 60)
    
    # Test 1: Basic Bedrock cost tracking
    print("\n1. Testing basic Bedrock cost tracking:")
    
    @track(type="llm")
    def bedrock_llm_call(input_text: str):
        """Simulate a Bedrock LLM call with cost tracking."""
        
        # Update the span with Bedrock provider and model
        opik_context.update_current_span(
            provider="bedrock",
            model="anthropic.claude-3-5-sonnet-20240620-v1:0",
            usage={
                "prompt_tokens": 100,
                "completion_tokens": 50,
                "total_tokens": 150
            }
        )
        
        print(f"   Input: {input_text}")
        print(f"   Provider: bedrock")
        print(f"   Model: anthropic.claude-3-5-sonnet-20240620-v1:0")
        print(f"   Usage: {{'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}}")
        
        return "This is a test response from Bedrock Claude 3.5 Sonnet."
    
    # Execute the test
    result = bedrock_llm_call("Hello, this is a test of Bedrock cost tracking!")
    print(f"   Output: {result}")
    
    # Test 2: Different Bedrock model
    print("\n2. Testing different Bedrock model:")
    
    @track(type="llm")
    def bedrock_claude_v2_call(input_text: str):
        """Test with Claude v2 model."""
        
        opik_context.update_current_span(
            provider="bedrock",
            model="anthropic.claude-v2",
            usage={
                "prompt_tokens": 200,
                "completion_tokens": 100,
                "total_tokens": 300
            }
        )
        
        print(f"   Input: {input_text}")
        print(f"   Provider: bedrock")
        print(f"   Model: anthropic.claude-v2")
        print(f"   Usage: {{'prompt_tokens': 200, 'completion_tokens': 100, 'total_tokens': 300}}")
        
        return "This is a test response from Bedrock Claude v2."
    
    # Execute the test
    result = bedrock_claude_v2_call("Test with Claude v2 model")
    print(f"   Output: {result}")
    
    # Test 3: User's original example
    print("\n3. Testing user's original example:")
    
    @track(type="llm")
    def user_example_call(input_text: str):
        """User's original example with cost tracking."""
        
        opik_context.update_current_span(
            provider="bedrock",
            model="anthropic.claude-3-5-sonnet-20240620-v1:0",
            usage={
                "prompt_tokens": 4,
                "completion_tokens": 6,
                "total_tokens": 10
            }
        )
        
        print(f"   Input: {input_text}")
        print(f"   Provider: bedrock")
        print(f"   Model: anthropic.claude-3-5-sonnet-20240620-v1:0")
        print(f"   Usage: {{'prompt_tokens': 4, 'completion_tokens': 6, 'total_tokens': 10}}")
        
        return "Hello, world!"
    
    # Execute the test
    result = user_example_call("Hello world!")
    print(f"   Output: {result}")
    
    print("\n✅ All tests completed!")
    print("\n📝 Next steps:")
    print("1. Open http://localhost:5173 in your browser")
    print("2. Navigate to the traces/spans to see cost tracking")
    print("3. Check that Bedrock models show estimated costs")

if __name__ == "__main__":
    # Set up environment for local testing
    os.environ.setdefault('OPIK_API_KEY', 'test-api-key')
    os.environ.setdefault('OPIK_WORKSPACE', 'test-workspace')
    os.environ.setdefault('OPIK_PROJECT', 'test-project')
    
    # Run the tests
    test_bedrock_cost_tracking()
    
    print(f"\n🕐 Test completed at: {datetime.now()}")
    print("🌐 Open http://localhost:5173 to view results in the UI") 
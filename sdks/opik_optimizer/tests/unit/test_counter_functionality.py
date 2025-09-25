"""
Test counter functionality across optimizers.
"""

import pytest
from unittest.mock import Mock, patch
from opik_optimizer.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.meta_prompt_optimizer.meta_prompt_optimizer import MetaPromptOptimizer
from opik_optimizer.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.evolutionary_optimizer.evolutionary_optimizer import EvolutionaryOptimizer
from opik_optimizer.mipro_optimizer.mipro_optimizer import MiproOptimizer
from opik_optimizer.optimization_config import chat_prompt
from opik import Dataset


class TestCounterFunctionality:
    """Test that counters work correctly across all optimizers."""

    def test_base_optimizer_counters_initialized(self):
        """Test that base optimizer initializes counters correctly."""
        from opik_optimizer.base_optimizer import BaseOptimizer
        
        # Create a mock optimizer that inherits from BaseOptimizer
        class MockOptimizer(BaseOptimizer):
            def optimize_prompt(self, *args, **kwargs):
                pass
                
            def optimize_mcp(self, *args, **kwargs):
                pass
        
        optimizer = MockOptimizer(model="gpt-4o-mini")
        
        # Check that counters are initialized
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0
        
        # Test counter methods
        optimizer.increment_llm_counter()
        assert optimizer.llm_call_counter == 1
        
        optimizer.increment_tool_counter()
        assert optimizer.tool_call_counter == 1
        
        optimizer.reset_counters()
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_gepa_optimizer_counters(self):
        """Test that GepaOptimizer has proper counters."""
        optimizer = GepaOptimizer(model="gpt-4o-mini")
        
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0
        
        # Test counter methods
        optimizer.increment_llm_counter()
        optimizer.increment_tool_counter()
        
        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_meta_prompt_optimizer_counters(self):
        """Test that MetaPromptOptimizer has proper counters."""
        optimizer = MetaPromptOptimizer(model="gpt-4o-mini")
        
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0
        
        # Test counter methods
        optimizer.increment_llm_counter()
        optimizer.increment_tool_counter()
        
        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_few_shot_bayesian_optimizer_counters(self):
        """Test that FewShotBayesianOptimizer has proper counters."""
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini")
        
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0
        
        # Test counter methods
        optimizer.increment_llm_counter()
        optimizer.increment_tool_counter()
        
        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_evolutionary_optimizer_counters(self):
        """Test that EvolutionaryOptimizer has proper counters."""
        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini")
        
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0
        
        # Test counter methods
        optimizer.increment_llm_counter()
        optimizer.increment_tool_counter()
        
        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_mipro_optimizer_counters(self):
        """Test that MiproOptimizer has proper counters."""
        optimizer = MiproOptimizer(model="gpt-4o-mini")
        
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0
        
        # Test counter methods
        optimizer.increment_llm_counter()
        optimizer.increment_tool_counter()
        
        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_reset_counters_method(self):
        """Test that reset_counters method works correctly."""
        optimizer = GepaOptimizer(model="gpt-4o-mini")
        
        # Increment counters
        optimizer.increment_llm_counter()
        optimizer.increment_llm_counter()
        optimizer.increment_tool_counter()
        
        assert optimizer.llm_call_counter == 2
        assert optimizer.tool_call_counter == 1
        
        # Reset counters
        optimizer.reset_counters()
        
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_agent_has_optimizer_reference(self):
        """Test that agents created by optimizers have access to the optimizer."""
        optimizer = GepaOptimizer(model="gpt-4o-mini")
        
        # Create a mock prompt
        prompt = chat_prompt.ChatPrompt(
            system="You are a helpful assistant.",
            user="Answer: {text}"
        )
        
        # Setup agent class
        agent_class = optimizer.setup_agent_class(prompt)
        agent = agent_class(prompt)
        
        # Check that agent has optimizer reference
        assert hasattr(agent, 'optimizer')
        assert agent.optimizer is optimizer

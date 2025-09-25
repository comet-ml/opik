"""
Tests for optimizer signature consistency.

This module tests that all optimizers have consistent method signatures
for optimize_prompt and optimize_mcp methods.
"""

import inspect
import pytest

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.evolutionary_optimizer.evolutionary_optimizer import EvolutionaryOptimizer
from opik_optimizer.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.meta_prompt_optimizer.meta_prompt_optimizer import MetaPromptOptimizer
from opik_optimizer.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.mipro_optimizer.mipro_optimizer import MiproOptimizer


class TestOptimizerSignatureConsistency:
    """Test that all optimizers have consistent method signatures."""

    @pytest.fixture
    def all_optimizers(self):
        """Get all optimizer classes."""
        return [
            ('BaseOptimizer', BaseOptimizer),
            ('EvolutionaryOptimizer', EvolutionaryOptimizer),
            ('FewShotBayesianOptimizer', FewShotBayesianOptimizer),
            ('MetaPromptOptimizer', MetaPromptOptimizer),
            ('GepaOptimizer', GepaOptimizer),
            ('MiproOptimizer', MiproOptimizer),
        ]

    def test_optimize_prompt_return_types_consistency(self, all_optimizers):
        """Test that all optimize_prompt methods return the same type."""
        from opik_optimizer.optimization_result import OptimizationResult
        
        for name, optimizer_class in all_optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            return_annotation = sig.return_annotation
            
            assert return_annotation == OptimizationResult, (
                f"{name}.optimize_prompt returns {return_annotation}, "
                f"expected {OptimizationResult}"
            )

    def test_optimize_mcp_return_types_consistency(self, all_optimizers):
        """Test that all optimize_mcp methods return the same type."""
        from opik_optimizer.optimization_result import OptimizationResult
        
        for name, optimizer_class in all_optimizers:
            if hasattr(optimizer_class, 'optimize_mcp'):
                sig = inspect.signature(optimizer_class.optimize_mcp)
                return_annotation = sig.return_annotation
                
                assert return_annotation == OptimizationResult, (
                    f"{name}.optimize_mcp returns {return_annotation}, "
                    f"expected {OptimizationResult}"
                )

    def test_optimize_prompt_core_parameters_consistency(self, all_optimizers):
        """Test that all optimize_prompt methods have the same core parameters."""
        # Define the expected core parameters (excluding self and kwargs)
        expected_core_params = ['prompt', 'dataset', 'metric', 'experiment_config']
        
        for name, optimizer_class in all_optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            params = list(sig.parameters.keys())
            
            # Check that core parameters are present
            for expected_param in expected_core_params:
                assert expected_param in params, (
                    f"{name}.optimize_prompt missing required parameter: {expected_param}. "
                    f"Found parameters: {params}"
                )

    def test_optimize_prompt_parameter_order_consistency(self, all_optimizers):
        """Test that all optimize_prompt methods have consistent parameter order."""
        # Define the expected parameter order (excluding self and kwargs)
        expected_order = ['prompt', 'dataset', 'metric', 'experiment_config']
        
        for name, optimizer_class in all_optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            params = list(sig.parameters.keys())
            
            # Remove 'self' and 'kwargs' for comparison
            core_params = [p for p in params if p not in ['self', 'kwargs']]
            
            # Check that the first 4 parameters match expected order
            for i, expected_param in enumerate(expected_order):
                if i < len(core_params):
                    assert core_params[i] == expected_param, (
                        f"{name}.optimize_prompt parameter {i} is '{core_params[i]}', "
                        f"expected '{expected_param}'. Full params: {params}"
                    )

    def test_optimize_mcp_core_parameters_consistency(self, all_optimizers):
        """Test that all optimize_mcp methods have the same core parameters."""
        # Define the expected core parameters for optimize_mcp
        expected_core_params = ['prompt', 'dataset', 'metric', 'tool_name', 'second_pass', 'experiment_config', 'n_samples', 'auto_continue', 'agent_class', 'fallback_invoker', 'fallback_arguments', 'allow_tool_use_on_second_pass']
        
        for name, optimizer_class in all_optimizers:
            if hasattr(optimizer_class, 'optimize_mcp'):
                sig = inspect.signature(optimizer_class.optimize_mcp)
                params = list(sig.parameters.keys())
                
                # Check that core parameters are present
                for expected_param in expected_core_params:
                    assert expected_param in params, (
                        f"{name}.optimize_mcp missing required parameter: {expected_param}. "
                        f"Found parameters: {params}"
                    )

    def test_optimize_mcp_parameter_order_consistency(self, all_optimizers):
        """Test that all optimize_mcp methods have consistent parameter order."""
        # Define the expected parameter order for optimize_mcp
        expected_order = ['prompt', 'dataset', 'metric', 'tool_name', 'second_pass', 'experiment_config']
        
        for name, optimizer_class in all_optimizers:
            if hasattr(optimizer_class, 'optimize_mcp'):
                sig = inspect.signature(optimizer_class.optimize_mcp)
                params = list(sig.parameters.keys())
                
                # Remove 'self' and 'kwargs' for comparison
                core_params = [p for p in params if p not in ['self', 'kwargs']]
                
                # Check that the first 6 parameters match expected order
                for i, expected_param in enumerate(expected_order):
                    if i < len(core_params):
                        assert core_params[i] == expected_param, (
                            f"{name}.optimize_mcp parameter {i} is '{core_params[i]}', "
                            f"expected '{expected_param}'. Full params: {params}"
                        )

    def test_optimize_prompt_optional_parameters_consistency(self, all_optimizers):
        """Test that all optimize_prompt methods have consistent optional parameters."""
        # Define the expected optional parameters
        expected_optional_params = ['n_samples', 'auto_continue', 'agent_class']
        
        for name, optimizer_class in all_optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            params = list(sig.parameters.keys())
            
            # Check that optional parameters are present (if they exist)
            for expected_param in expected_optional_params:
                if expected_param in params:
                    param = sig.parameters[expected_param]
                    # Check that it has a default value (making it optional)
                    assert param.default != inspect.Parameter.empty, (
                        f"{name}.optimize_prompt parameter '{expected_param}' should be optional "
                        f"(have a default value)"
                    )

    def test_signature_inconsistencies_detected(self, all_optimizers):
        """Test that detects and reports signature inconsistencies."""
        inconsistencies = []
        
        # Check optimize_prompt signatures
        base_sig = inspect.signature(BaseOptimizer.optimize_prompt)
        base_params = list(base_sig.parameters.keys())
        
        for name, optimizer_class in all_optimizers:
            if optimizer_class == BaseOptimizer:
                continue
                
            sig = inspect.signature(optimizer_class.optimize_prompt)
            params = list(sig.parameters.keys())
            
            if params != base_params:
                inconsistencies.append({
                    'method': 'optimize_prompt',
                    'optimizer': name,
                    'expected': base_params,
                    'actual': params
                })
        
        # Check optimize_mcp signatures
        if hasattr(BaseOptimizer, 'optimize_mcp'):
            base_mcp_sig = inspect.signature(BaseOptimizer.optimize_mcp)
            base_mcp_params = list(base_mcp_sig.parameters.keys())
            
            for name, optimizer_class in all_optimizers:
                if optimizer_class == BaseOptimizer:
                    continue
                    
                if hasattr(optimizer_class, 'optimize_mcp'):
                    sig = inspect.signature(optimizer_class.optimize_mcp)
                    params = list(sig.parameters.keys())
                    
                    if params != base_mcp_params:
                        inconsistencies.append({
                            'method': 'optimize_mcp',
                            'optimizer': name,
                            'expected': base_mcp_params,
                            'actual': params
                        })
        
        # Report inconsistencies
        if inconsistencies:
            error_msg = "Signature inconsistencies detected:\n"
            for inc in inconsistencies:
                error_msg += (
                    f"- {inc['optimizer']}.{inc['method']}:\n"
                    f"  Expected: {inc['expected']}\n"
                    f"  Actual:   {inc['actual']}\n"
                )
            
            pytest.fail(error_msg)

    def test_all_optimizers_have_optimize_mcp(self, all_optimizers):
        """Test that all optimizers have the optimize_mcp method."""
        for name, optimizer_class in all_optimizers:
            assert hasattr(optimizer_class, 'optimize_mcp'), (
                f"{name} is missing the optimize_mcp method"
            )

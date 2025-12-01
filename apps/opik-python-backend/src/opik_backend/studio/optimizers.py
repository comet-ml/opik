"""Optimizer factory for Optimization Studio."""

import logging
from typing import Dict, Type, Any

from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.algorithms.evolutionary_optimizer.evolutionary_optimizer import EvolutionaryOptimizer
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_reflective_optimizer import HierarchicalReflectiveOptimizer

logger = logging.getLogger(__name__)


class OptimizerFactory:
    """Factory for creating optimizer instances.
    
    Maps optimizer type strings to their corresponding optimizer classes.
    Makes it easy to add new optimizers without modifying the main job processor.
    """
    
    _OPTIMIZERS: Dict[str, Type] = {
        "gepa": GepaOptimizer,
        "evolutionary": EvolutionaryOptimizer,
        "hierarchical_reflective": HierarchicalReflectiveOptimizer,
    }
    
    @classmethod
    def build(
        cls,
        optimizer_type: str,
        model: str,
        model_params: Dict[str, Any],
        optimizer_params: Dict[str, Any]
    ):
        """Build an optimizer instance from config.
        
        Args:
            optimizer_type: Type of optimizer (e.g., "gepa", "evolutionary", "hierarchical_reflective")
            model: LLM model identifier
            model_params: Model parameters (e.g., temperature, max_tokens)
            optimizer_params: Optimizer-specific parameters (e.g., n_iterations)
            
        Returns:
            Initialized optimizer instance
            
        Raises:
            ValueError: If optimizer_type is not recognized
        """
        optimizer_type = optimizer_type.lower()
        
        if optimizer_type not in cls._OPTIMIZERS:
            available = ", ".join(sorted(cls._OPTIMIZERS.keys()))
            raise ValueError(
                f"Unknown optimizer type: '{optimizer_type}'. "
                f"Available optimizers: {available}"
            )
        
        logger.info(f"Initializing {optimizer_type} optimizer with params: {optimizer_params}")
        
        optimizer_class = cls._OPTIMIZERS[optimizer_type]
        optimizer = optimizer_class(
            model=model,
            model_parameters=model_params,
            **optimizer_params
        )
        
        logger.info(f"Created {optimizer_type} optimizer instance")
        return optimizer
    
    @classmethod
    def list_available(cls) -> list:
        """List all available optimizer types.
        
        Returns:
            List of optimizer type strings
        """
        return sorted(cls._OPTIMIZERS.keys())


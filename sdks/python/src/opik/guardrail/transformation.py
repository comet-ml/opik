from typing import Any, Callable, Optional
from opik.utils.naming_utils import random_id

class Transformation:
    """A class that defines a transformation to be applied to data before validation.
    
    Attributes:
        transform_fn: The function that performs the transformation
        name: Optional name for the transformation
        description: Optional description of what the transformation does
    """
    
    def __init__(
        self,
        transform_fn: Callable,
        name: Optional[str] = None,
        description: Optional[str] = None
    ):
        self.transform_fn = transform_fn
        self.name = name or f"transform-{random_id()}"
        self.description = description

    def apply(self, input_data: Any) -> Any:
        """Apply the transformation to the input data.
        
        Args:
            input_data: The data to transform
            
        Returns:
            The transformed data
        """
        return self.transform_fn(input_data) 
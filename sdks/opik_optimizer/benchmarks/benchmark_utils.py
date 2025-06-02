from pathlib import Path
from typing import Any

from opik.evaluation.metrics import BaseMetric


# Custom JSON serializer to handle specific object types
def custom_json_serializer(obj: Any) -> Any:
    """Custom JSON serializer to handle specific object types."""
    if isinstance(obj, BaseMetric):
        # For metric objects, return their string representation or a more detailed dict if available
        # For simplicity, str(obj) often gives a good summary (e.g., class name)
        return str(obj) 
    # For datetime objects not handled by pydantic model_dump
    if hasattr(obj, 'isoformat'):
        return obj.isoformat()
    if isinstance(obj, Path):
        return str(obj.resolve())
    # For any other types that json.dump can't handle by default
    # Consider adding more specific handlers if other unserializable types appear
    try:
        return str(obj) # Fallback to string representation for other unknown complex types
    except Exception:
        raise TypeError(f"Object of type {obj.__class__.__name__} is not JSON serializable and str() failed")

# Function to clean metric name representation
def clean_metric_name(metric_key_str: str) -> str:
    """Extracts class name like 'LevenshteinRatio' from string representations."""
    if isinstance(metric_key_str, str) and '<' in metric_key_str and 'object at' in metric_key_str:
         # Extract the class name part more robustly
         parts = metric_key_str.split('.')
         if len(parts) > 1:
             name = parts[-1]
             if ' object at' in name:
                 name = name.split(' object at')[0]
             return name
         else: # Fallback if format is unexpected
             return metric_key_str.strip('<> ') 
    return metric_key_str # Return as is if not matching the object format

#!/usr/bin/env python3
"""
Enhanced Optimized Scoring Runner
Balanced approach combining research optimizations with practical stability:
- Local imports for heavy modules only
- python -S benefits while keeping essential modules
- Lazy loading for opik-specific imports
"""

import sys
import json
import traceback
import uuid
from types import ModuleType
from typing import Type, Union, List

# Add site-packages manually for python -S compatibility
# More conservative than ultra-optimized approach
sys.path.insert(0, '/usr/local/lib/python3.12/site-packages')

# OPTIMIZATION 1: Lazy import heavy opik modules only when needed
_opik_imports_loaded = False
_BaseMetric = None
_ScoreResult = None

def load_opik_imports():
    """Load opik imports lazily - only when actually needed."""
    global _opik_imports_loaded, _BaseMetric, _ScoreResult
    if not _opik_imports_loaded:
        from opik.evaluation.metrics import BaseMetric
        from opik.evaluation.metrics.score_result import ScoreResult
        _BaseMetric = BaseMetric
        _ScoreResult = ScoreResult
        _opik_imports_loaded = True

    return _BaseMetric, _ScoreResult

# OPTIMIZATION 2: Pre-load lightweight inspection modules
import inspect

# Constants
TRACE_THREAD_METRIC_TYPE = "trace_thread"

def get_metric_class(module: ModuleType) -> Type:
    """Find BaseMetric subclass in the provided module."""
    BaseMetric, ScoreResult = load_opik_imports()
    
    for _, cls in inspect.getmembers(module, inspect.isclass):
        if issubclass(cls, BaseMetric):
            return cls

def to_scores(score_result) -> List:
    """Convert score result to list of ScoreResult objects."""
    BaseMetric, ScoreResult = load_opik_imports()
    
    scores = []
    if isinstance(score_result, ScoreResult):
        scores = [score_result]
    elif isinstance(score_result, list):
        for item in score_result:
            if isinstance(item, ScoreResult):
                scores.append(item)
    return scores

def execute_scoring(code: str, data: dict, payload_type: str = None) -> dict:
    """Execute scoring code matching actual production behavior - all errors use evaluation format."""

    module = ModuleType(str(uuid.uuid4()))
    
    try:
        exec(code, module.__dict__)
        metric_class = get_metric_class(module)
        if metric_class is None:
            return {"error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'"}
        
        metric = metric_class()
        if payload_type == TRACE_THREAD_METRIC_TYPE:
            score_result = metric.score(data)
        else:
            score_result = metric.score(**data)
            
    except Exception as e:
        # Match actual production behavior: ALL errors use the same evaluation format  
        stacktrace = "\n".join(traceback.format_exc().splitlines()[2:])
        return {"error": f"The provided 'code' and 'data' fields can't be evaluated: {stacktrace}"}
        
    scores = to_scores(score_result)
    result = {"scores": [s.__dict__ for s in scores]}
    
    return result

def main():
    """Main execution function with enhanced optimizations."""
    
    if len(sys.argv) < 3:
        print(json.dumps({"code": 400, "error": "Usage: enhanced_runner.py <code> <data_json> [payload_type]"}), file=sys.stderr)
        sys.exit(1)
        
    try:
        code = sys.argv[1]
        data = json.loads(sys.argv[2])
        payload_type = sys.argv[3] if len(sys.argv) > 3 else None
        
        result = execute_scoring(code, data, payload_type)
        print(json.dumps(result))
        
    except json.JSONDecodeError as e:
        print(json.dumps({"code": 400, "error": f"Invalid JSON in data parameter: {str(e)}"}))
        sys.exit(1)
    except Exception as e:
        print(json.dumps({"code": 500, "error": f"Unexpected error: {str(e)}"}))
        sys.exit(1)

if __name__ == "__main__":
    main()

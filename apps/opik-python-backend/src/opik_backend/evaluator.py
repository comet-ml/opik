import asyncio
import os
import traceback
from typing import Any, Dict
import logging
import time

from flask import request, abort, jsonify, Blueprint, current_app, copy_current_request_context
from werkzeug.exceptions import HTTPException

from opik_backend.executor import CodeExecutorBase
from opik_backend.http_utils import build_error_response

# Environment variable to control execution strategy
EXECUTION_STRATEGY = os.getenv("PYTHON_CODE_EXECUTOR_STRATEGY", "process")

evaluator = Blueprint('evaluator', __name__, url_prefix='/v1/private/evaluators')

def init_executor(app):
    """Initialize the code executor when the Flask app starts."""
    import logging
    logger = logging.getLogger(__name__)
    logger.info(f"🔍 Initializing executor with strategy: {EXECUTION_STRATEGY}")
    
    if EXECUTION_STRATEGY == "docker":
        logger.info("🐳 Creating DockerExecutor...")
        from opik_backend.executor_docker import DockerExecutor
        app.executor = DockerExecutor()
        logger.info(f"✅ DockerExecutor created and assigned: {type(app.executor).__name__}")
        logger.info(f"✅ app.executor max_parallel: {app.executor.max_parallel}")
    elif EXECUTION_STRATEGY == "process":
        logger.info("⚙️ Creating ProcessExecutor...")
        from opik_backend.executor_process import ProcessExecutor
        process_executor = ProcessExecutor()
        app.executor = process_executor
        logger.info(f"✅ ProcessExecutor created and assigned: {type(app.executor).__name__}")
    else:
        raise ValueError(f"Unknown execution strategy: {EXECUTION_STRATEGY}")
        

def get_executor() -> CodeExecutorBase:
    """Get the executor instance from the Flask app context."""
    import logging
    logger = logging.getLogger(__name__)
    
    if not hasattr(current_app, 'executor'):
        logger.error(f"❌ current_app has no 'executor' attribute! Available attributes: {dir(current_app)}")
        raise RuntimeError("Executor not initialized on Flask app")
        
    executor = current_app.executor
    if executor is None:
        logger.error("❌ current_app.executor is None!")
        raise RuntimeError("Executor is None")
        
    logger.debug(f"✅ Retrieved executor: {type(executor).__name__}")
    return executor


@evaluator.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)

@evaluator.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)

@evaluator.route("/python", methods=["POST"])
async def execute_evaluator_python():
    if request.method != "POST":
        return

    try:
        # Start timing the request
        start_time = time.time()
        
        # Handle blocking request.get_json() asynchronously
        loop = asyncio.get_event_loop()
        payload: Any = await loop.run_in_executor(None, request.get_json, True)
        
        if not payload:
            abort(400, description="No JSON payload provided")

        code = payload.get("code")
        data = payload.get("data")

        if not code:
            abort(400, description="Field 'code' is missing in the request")

        if not data:
            abort(400, description="Field 'data' is missing in the request")

        # Get the executor and run the scoring code
        executor = get_executor()
        result = await executor.run_scoring(code, data)

        # Calculate and print latency
        latency = time.time() - start_time
        print(f"Request completed in {latency*1000:.1f} milliseconds")

        return jsonify(result)
        
    except Exception as e:
        import logging
        logger = logging.getLogger(__name__)
        logger.error(f"❌ Full error details: {str(e)}")
        logger.error(f"🔍 Traceback: {traceback.format_exc()}")
        
        if "bound to a different event loop" in str(e):
            logger.error("🚨 Event loop binding error detected!")
            logger.error(f"🔍 Current event loop: {asyncio.get_event_loop()}")
            
        abort(500, description=f"Failed to execute code: {str(e)}")

#!/usr/bin/env python3
"""
Simple test server to verify custom headers from Opik custom providers.
Mimics vLLM's OpenAI-compatible API and logs all received headers.
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import logging
from datetime import datetime

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

class CustomProviderHandler(BaseHTTPRequestHandler):
    """Handler that mimics vLLM API and logs headers"""
    
    def log_message(self, format, *args):
        """Override to use our logger"""
        pass
    
    def log_headers(self):
        """Log all received headers"""
        logger.info("=" * 80)
        logger.info(f"REQUEST: {self.command} {self.path}")
        logger.info("HEADERS RECEIVED:")
        for header, value in self.headers.items():
            logger.info(f"  {header}: {value}")
        logger.info("=" * 80)
    
    def do_GET(self):
        """Handle GET requests"""
        self.log_headers()
        
        if self.path == '/v1/models':
            # Return list of available models
            response = {
                "object": "list",
                "data": [
                    {
                        "id": "meta-llama/Meta-Llama-3.1-70B",
                        "object": "model",
                        "created": 1234567890,
                        "owned_by": "test-provider"
                    },
                    {
                        "id": "mistralai/Mistral-7B",
                        "object": "model",
                        "created": 1234567890,
                        "owned_by": "test-provider"
                    },
                    {
                        "id": "search-rl-qwen-2-5-vl-7b",
                        "object": "model",
                        "created": 1234567890,
                        "owned_by": "snap-test"
                    }
                ]
            }
            self.send_json_response(response)
        elif self.path == '/health' or self.path == '/':
            # Health check endpoint
            response = {"status": "ok", "service": "test-custom-provider"}
            self.send_json_response(response)
        else:
            self.send_error(404, "Not Found")
    
    def do_POST(self):
        """Handle POST requests"""
        self.log_headers()
        
        # Read request body
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length) if content_length > 0 else b'{}'
        
        try:
            request_data = json.loads(body) if body else {}
            logger.info("REQUEST BODY:")
            logger.info(f"  {json.dumps(request_data, indent=2)}")
        except json.JSONDecodeError:
            logger.warning("Failed to parse request body as JSON")
            request_data = {}
        
        if self.path == '/v1/chat/completions':
            # Return OpenAI-compatible chat completion response
            response = {
                "id": "chatcmpl-test-123",
                "object": "chat.completion",
                "created": int(datetime.now().timestamp()),
                "model": request_data.get("model", "test-model"),
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "This is a test response from the custom provider server. Your custom headers were received successfully!"
                        },
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30
                }
            }
            self.send_json_response(response)
        elif self.path == '/v1/completions':
            # Return OpenAI-compatible completion response
            response = {
                "id": "cmpl-test-123",
                "object": "text_completion",
                "created": int(datetime.now().timestamp()),
                "model": request_data.get("model", "test-model"),
                "choices": [
                    {
                        "text": "Test completion response with custom headers verified!",
                        "index": 0,
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": 5,
                    "completion_tokens": 10,
                    "total_tokens": 15
                }
            }
            self.send_json_response(response)
        else:
            response = {
                "error": f"Endpoint {self.path} not implemented",
                "status": "ok",
                "note": "Custom headers were logged successfully"
            }
            self.send_json_response(response)
    
    def send_json_response(self, data, status=200):
        """Send JSON response"""
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.end_headers()
        self.wfile.write(json.dumps(data, indent=2).encode())
    
    def do_OPTIONS(self):
        """Handle OPTIONS requests for CORS preflight"""
        self.log_headers()
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.end_headers()


def run_server(port=8080):
    """Run the test server"""
    server_address = ('', port)
    httpd = HTTPServer(server_address, CustomProviderHandler)
    
    logger.info("=" * 80)
    logger.info(f"🚀 Custom Provider Test Server Starting")
    logger.info(f"📡 Listening on port {port}")
    logger.info(f"🔗 Test URL: http://137.184.166.152:{port}/v1")
    logger.info("=" * 80)
    logger.info("Available endpoints:")
    logger.info("  GET  /v1/models              - List available models")
    logger.info("  POST /v1/chat/completions    - Chat completion")
    logger.info("  POST /v1/completions         - Text completion")
    logger.info("  GET  /health                 - Health check")
    logger.info("=" * 80)
    logger.info("✅ Server ready! All request headers will be logged below.")
    logger.info("=" * 80)
    
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        logger.info("\n" + "=" * 80)
        logger.info("🛑 Server stopped by user")
        logger.info("=" * 80)


if __name__ == '__main__':
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    run_server(port)


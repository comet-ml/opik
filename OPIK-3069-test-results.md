# OPIK-3069 - Test Results & Documentation

## ✅ Implementation Complete

**Pull Request:** https://github.com/comet-ml/opik/pull/4069

**Branch:** `nimrodlahav/OPIK-3069-add-custom-headers-support-for-custom-providers`

---

## Test Script

To verify custom headers are sent correctly, use this test server script that mimics vLLM's API:

**File:** `test_custom_provider_server.py`

```python
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
                        "id": "search-rl-qwen-2-5-vl-7b",
                        "object": "model",
                        "created": 1234567890,
                        "owned_by": "snap-test"
                    }
                ]
            }
            self.send_json_response(response)
        elif self.path == '/health' or self.path == '/':
            response = {"status": "ok", "service": "test-custom-provider"}
            self.send_json_response(response)
        else:
            self.send_error(404, "Not Found")
    
    def do_POST(self):
        """Handle POST requests"""
        self.log_headers()
        
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
            response = {
                "id": "chatcmpl-test-123",
                "object": "chat.completion",
                "created": int(datetime.now().timestamp()),
                "model": request_data.get("model", "test-model"),
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "This is a test response from the custom provider server. Your custom headers were received successfully!"
                    },
                    "finish_reason": "stop"
                }],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30
                }
            }
            self.send_json_response(response)
        elif self.path == '/v1/completions':
            response = {
                "id": "cmpl-test-123",
                "object": "text_completion",
                "created": int(datetime.now().timestamp()),
                "model": request_data.get("model", "test-model"),
                "choices": [{
                    "text": "Test completion response with custom headers verified!",
                    "index": 0,
                    "finish_reason": "stop"
                }],
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
        """Handle OPTIONS for CORS"""
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
    logger.info(f"🔗 Test URL: http://localhost:{port}/v1")
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
```

### Usage

```bash
# Start the test server
python3 test_custom_provider_server.py 8080

# Configure in Opik Playground:
# - Provider: Custom provider
# - URL: http://YOUR_SERVER:8080/v1
# - Custom Header: my-CustomerX-header = 1234s/dfln@%@#fEWASZ
```

---

## 🧪 Test Results

### Test Configuration
- **Provider Name:** test-provider
- **URL:** http://137.184.166.152:8080/v1
- **Model:** some model
- **API Key:** some api key
- **Custom Header:** `my-CustomerX-header: 1234s/dfln@%@#fEWASZ`

### Server Output (Headers Successfully Received)

```
2025-11-14 11:17:47 - ================================================================================
2025-11-14 11:17:47 - REQUEST: POST /v1/chat/completions
2025-11-14 11:17:47 - HEADERS RECEIVED:
2025-11-14 11:17:47 -   Content-Length: 317
2025-11-14 11:17:47 -   Host: 137.184.166.152:8080
2025-11-14 11:17:47 -   User-Agent: Java-http-client/24.0.1
2025-11-14 11:17:47 -   Authorization: Bearer some api key
2025-11-14 11:17:47 -   Content-Type: application/json
2025-11-14 11:17:47 -   my-CustomerX-header: 1234s/dfln@%@#fEWASZ  ✅ CUSTOM HEADER RECEIVED
2025-11-14 11:17:47 - ================================================================================
2025-11-14 11:17:47 - REQUEST BODY:
2025-11-14 11:17:47 -   {
  "model": "some model",
  "messages": [
    {
      "role": "user",
      "content": "how are you today?"
    }
  ],
  "temperature": 0.0,
  "top_p": 1.0,
  "stream": true,
  "stream_options": {
    "include_usage": true
  },
  "max_completion_tokens": 4000,
  "presence_penalty": 0.0,
  "frequency_penalty": 0.0
}
```

### Verification Steps Completed

✅ **Create Custom Provider with Headers**
- Added custom provider with test header
- Header appears correctly in the form
- Header persisted after saving
- Header visible when re-opening provider configuration

✅ **Update Custom Provider**
- Added additional headers to existing provider
- Modified header values
- Successfully updated and persisted

✅ **Delete Headers**
- Removed individual headers using trash icon (immediately disappears)
- Removed all headers (provider returns to clean state with empty headers section)
- Empty object `{}` sent to backend to clear headers on update

✅ **End-to-End API Test**
- Custom header successfully received by test server
- Header value matches exactly what was configured in UI
- Backend properly forwards headers to custom provider endpoint
- Authorization header also correctly sent

✅ **Edge Cases Tested**
- Empty headers (not sent to backend)
- Special characters in header values (e.g., `@%#/`)
- Multiple headers (can add/remove multiple)
- Header deletion UI (trash icon works immediately)

---

## Feature Summary

### What Was Implemented

1. **Type Definitions** - Added `headers?: Record<string, string>` to provider types
2. **Form Schema** - Added optional headers validation
3. **UI Component** - Dynamic key-value pair inputs with add/remove buttons
4. **Form State** - Proper conversion between array (UI) ↔ object (API)
5. **API Mutations** - Headers sent on create and update operations
6. **Bug Fixes** - Header deletion properly clears from backend

### Data Flow

```
UI (array format)                    API (object format)
[                                    {
  { key: "X-Auth", value: "123" }  →   "X-Auth": "123",
  { key: "X-Type", value: "vllm" }     "X-Type": "vllm"
]                                    }
```

### Files Changed
- `types/providers.ts` - Type definitions
- `ManageAIProviderDialog/schema.ts` - Form validation
- `ManageAIProviderDialog/CustomProviderDetails.tsx` - UI component
- `ManageAIProviderDialog/ManageAIProviderDialog.tsx` - Form state management
- `useProviderKeysCreateMutation.ts` - Create mutation
- `useProviderKeysUpdateMutation.ts` - Update mutation

### Commits
1. `[OPIK-3069] [FE] Add custom headers support for custom providers in Playground`
2. `Revision 2: Fix header deletion - send empty object to clear headers when updating provider`
3. `Revision 3: Fix header deletion UI - remove header immediately and send empty object to backend`
4. `[OPIK-3069] [DOCS] Add custom headers documentation for custom providers`
5. `[OPIK-3069] [DOCS] Update vLLM configuration screenshot to show custom headers UI`

---

## Documentation

### Updated Documentation
✅ **Configuration > AI Providers** page updated with custom headers documentation:
- Added "Custom Headers" section explaining the feature
- Included configuration steps with examples
- Documented common use cases (custom auth, request routing, metadata, enterprise features)
- Added security note about header values

**Documentation Location**: `apps/opik-documentation/documentation/fern/docs/configuration/ai_providers.mdx`

**Section**: vLLM / Custom Provider → Custom Headers

### Screenshot
✅ **Screenshot updated** - vLLM configuration screenshot now shows the custom headers UI with example header configuration.

## Next Steps

- ✅ PR created: https://github.com/comet-ml/opik/pull/4069
- ✅ Documentation updated
- ✅ Screenshot updated
- ⏳ Awaiting code review
- 🎯 Customer (Snap) can test with their endpoints once merged

---

## Customer Impact

**Snap Use Case:**
- Can now add required headers for `search-rl-qwen-2-5-vl-7b` model
- Custom endpoints with authentication headers fully supported
- No breaking changes for existing custom providers


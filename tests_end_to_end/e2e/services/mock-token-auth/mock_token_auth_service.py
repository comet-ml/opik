#!/usr/bin/env python3
"""OAuth2 client-credentials mock for the OPIK-7940 e2e suite (dynamic token auth).

One process, two roles:

  POST /oauth/token           OAuth2 token endpoint. Requires HTTP Basic
                              (CLIENT_ID/CLIENT_SECRET) and a form body with
                              grant_type=client_credentials, per the spec.
                              Returns {access_token, token_type, expires_in}.
                              A credential row "scope: ttl:<N>" overrides the
                              token lifetime for that request only.
  POST /v1/chat/completions   OpenAI-compatible gateway (sync + SSE streaming).
                              Accepts only bearers this process issued (or the
                              static API key), so a missing/stale bearer fails
                              loudly instead of silently passing.

Test hooks:

  GET  /health                Liveness for Playwright's webServer probe.
  GET  /stats                 Counters (tokens_issued, chat_ok, chat_refused_*)
                              for clock-free refetch assertions.
  POST /revoke                Invalidate every issued token (reactive-retry path).

Spawned automatically by the suite's `webServer` config; run by hand with
    python3 mock_token_auth_service.py --port 9878 --ttl 3600
"""

import argparse
import base64
import json
import secrets
import sys
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

CLIENT_ID = "opik-test"
CLIENT_SECRET = "opik-secret"
STATIC_API_KEY = "mock-static-key"  # accepted as a bearer, for the static-auth provider entry

TTL_SECONDS = 3600
ISSUED_TOKENS = {}  # token -> expiry epoch seconds
STATS = Counter()


def log(message):
    print(f"[mock-auth {time.strftime('%H:%M:%S')}] {message}", file=sys.stderr, flush=True)


def _value_for(schema):
    kind = schema.get("type")
    if kind == "object":
        return {name: _value_for(prop) for name, prop in (schema.get("properties") or {}).items()}
    if kind in ("number", "integer"):
        return 1
    if kind == "boolean":
        return True
    if kind == "array":
        return []
    return "mock gateway accepted the bearer token"


def synthesize_reply(request):
    """Builds content matching the response_format JSON schema when the caller sends one
    (the LLM-as-judge path does), so online scoring can parse the reply."""
    schema = (((request.get("response_format") or {}).get("json_schema") or {}).get("schema")) or {}
    if schema.get("properties"):
        return _value_for(schema)
    return {"score": 1.0, "reason": "mock gateway accepted the bearer token"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silence default access log; we log ourselves
        pass

    def _reply(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._reply(200, {"status": "ok"})
        elif self.path == "/stats":
            self._reply(200, dict(STATS))
        else:
            self._reply(404, {"error": "not_found", "path": self.path})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw_body = self.rfile.read(length) if length else b""

        if self.path == "/oauth/token":
            self._handle_token(raw_body)
        elif self.path == "/revoke":
            count = len(ISSUED_TOKENS)
            ISSUED_TOKENS.clear()
            STATS["revocations"] += 1
            log(f"REVOKED all {count} issued tokens (gateway will 401 until a fresh fetch)")
            self._reply(200, {"revoked": count})
        elif self.path.endswith("/chat/completions"):
            self._handle_chat(raw_body)
        else:
            self._reply(404, {"error": "not_found", "path": self.path})

    def _handle_token(self, raw_body):
        auth = self.headers.get("Authorization") or ""
        if not auth.startswith("Basic "):
            STATS["token_refused"] += 1
            log("token request REFUSED: no HTTP Basic header")
            self._reply(401, {"error": "invalid_client",
                              "error_description": "expected client_id/client_secret via HTTP Basic"})
            return
        try:
            client_id, client_secret = (
                base64.b64decode(auth.removeprefix("Basic ")).decode().split(":", 1))
        except Exception:
            STATS["token_refused"] += 1
            self._reply(401, {"error": "invalid_client"})
            return
        if (client_id, client_secret) != (CLIENT_ID, CLIENT_SECRET):
            STATS["token_refused"] += 1
            log(f"token request REFUSED: bad credentials for client_id='{client_id}'")
            self._reply(401, {"error": "invalid_client"})
            return

        form = parse_qs(raw_body.decode())
        grant_type = (form.get("grant_type") or [""])[0]
        if grant_type != "client_credentials":
            STATS["token_refused"] += 1
            log(f"token request REFUSED: grant_type='{grant_type}'")
            self._reply(400, {"error": "unsupported_grant_type",
                              "error_description": f"got grant_type='{grant_type}'"})
            return

        scope = (form.get("scope") or [""])[0]
        ttl = TTL_SECONDS
        if scope.startswith("ttl:"):  # per-provider TTL override, e.g. scope=ttl:10
            ttl = int(scope.split(":", 1)[1])

        token = secrets.token_urlsafe(24)
        ISSUED_TOKENS[token] = time.time() + ttl
        STATS["tokens_issued"] += 1
        log(f"token ISSUED (ttl={ttl}s, scope='{scope}'): ...{token[-8:]}")
        self._reply(200, {
            "access_token": token,
            "token_type": "Bearer",
            "expires_in": ttl,
            **({"scope": scope} if scope else {}),
        })

    def _handle_chat(self, raw_body):
        try:
            request = json.loads(raw_body)
        except Exception:
            request = {}
        model = request.get("model", "mock-model")

        # counters are kept globally AND per model: parallel specs use unique model
        # names, so the model-scoped counters let each spec assert on its own traffic
        def count(outcome):
            STATS[outcome] += 1
            STATS[f"{outcome}:{model}"] += 1

        auth = self.headers.get("Authorization") or ""
        token = auth.removeprefix("Bearer ") if auth.startswith("Bearer ") else None
        if token is None:
            count("chat_refused_missing")
            log(f"chat request REFUSED: missing bearer token (model={model})")
            self._reply(401, {"error": {"message": "missing bearer token", "type": "invalid_request_error"}})
            return
        if token == STATIC_API_KEY:
            expiry = float("inf")
        else:
            expiry = ISSUED_TOKENS.get(token)
        if expiry is None:
            count("chat_refused_unknown")
            log(f"chat request REFUSED: unknown bearer token (model={model})")
            self._reply(401, {"error": {"message": "invalid bearer token", "type": "invalid_request_error"}})
            return
        if expiry < time.time():
            count("chat_refused_expired")
            log(f"chat request REFUSED: expired token ...{token[-8:]} (model={model})")
            self._reply(401, {"error": {"message": "token expired", "type": "invalid_request_error"}})
            return

        content = json.dumps(synthesize_reply(request))
        usage = {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}
        count("chat_ok")

        if request.get("stream"):
            log(f"chat request OK (streaming) with token ...{token[-8:]}")
            self._reply_sse(model, content, usage)
            return

        log(f"chat request OK with token ...{token[-8:]}")
        self._reply(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [{
                "index": 0,
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": content},
            }],
            "usage": usage,
        })

    def _reply_sse(self, model, content, usage):
        def chunk(delta, finish_reason=None, **extra):
            return {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
                **extra,
            }

        events = [
            chunk({"role": "assistant"}),
            chunk({"content": content}),
            chunk({}, finish_reason="stop", usage=usage),
        ]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for event in events:
            self.wfile.write(f"data: {json.dumps(event)}\n\n".encode())
            self.wfile.flush()
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def main():
    global TTL_SECONDS
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=9878)
    parser.add_argument("--host", default="127.0.0.1",
                        help="bind address; CI uses 0.0.0.0 so the dockerized backend can reach the mock via the host")
    parser.add_argument("--ttl", type=int, default=3600, help="default token lifetime (expires_in) in seconds")
    args = parser.parse_args()
    TTL_SECONDS = args.ttl

    log(f"listening on http://{args.host}:{args.port} "
        f"(token URL /oauth/token, gateway /v1/chat/completions, ttl={TTL_SECONDS}s)")
    # fake, hardcoded test identities — still redacted so CodeQL's clear-text-logging check stays green
    log(f"client_id={CLIENT_ID} client_secret=[see CLIENT_SECRET constant] static_api_key=[see STATIC_API_KEY constant]")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()

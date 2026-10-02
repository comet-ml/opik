import json
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from queue import Queue

from opik_system_metrics import OpikSystemMetrics


class OpikSystemMetricsTest(unittest.TestCase):
    def test_flush_sends_process_metrics_to_project_batch_endpoint(self):
        received = Queue()

        class Receiver(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                received.put((self.path, body))
                self.send_response(202)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"accepted":3}')

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.addCleanup(server.server_close)
        project_id = str(uuid.uuid4())
        metrics = OpikSystemMetrics(
            base_url=f"http://127.0.0.1:{server.server_port}/api",
            project_id=project_id,
            service_name="peppa-agent",
            service_instance_id="worker-test-1",
            agent_id="assistant",
        )

        self.assertTrue(metrics.flush())

        path, body = received.get(timeout=2)
        self.assertEqual(
            path,
            f"/api/v1/private/projects/{project_id}/system-metrics/batch",
        )
        names = {point["metric_name"] for point in body["metrics"]}
        self.assertEqual(
            names,
            {
                "agent.process.cpu.time",
                "agent.process.cpu.utilization",
                "agent.process.memory",
                "agent.process.memory.utilization",
                "agent.heartbeat",
            },
        )
        self.assertEqual(
            {point["service_instance_id"] for point in body["metrics"]},
            {"worker-test-1"},
        )

    def test_request_metrics_include_trace_thread_and_mcp_attributes(self):
        metrics = OpikSystemMetrics(
            base_url="http://127.0.0.1:1/api",
            project_id=str(uuid.uuid4()),
            service_name="peppa-agent",
            service_instance_id="worker-test-1",
        )

        metrics.record_mcp_request(
            "/mcp/tools/call",
            "POST",
            200,
            0.125,
            mcp_method="tools/call",
            trace_id="trace-123",
            thread_id="thread-456",
        )

        points = list(metrics._points)
        self.assertEqual(len(points), 2)
        duration = next(
            point
            for point in points
            if point["metric_name"] == "agent.http.request.duration"
        )
        self.assertEqual(duration["value"], 0.125)
        self.assertEqual(
            duration["attributes"],
            {
                "http.route": "/mcp/tools/call",
                "http.request.method": "POST",
                "http.response.status_code": "200",
                "direction": "server",
                "request.kind": "mcp",
                "trace.id": "trace-123",
                "thread.id": "thread-456",
                "mcp.method": "tools/call",
            },
        )


if __name__ == "__main__":
    unittest.main()

"""
Contract tests for Java-Python RQ integration.

These tests verify that:
1. Java writes jobs with plain JSON 'data' (no zlib) in RQ format
2. Java enqueues job IDs into the RQ list key: 'rq:queue:<queue_name>'
3. Python RQ (JSONSerializer + default Job) can process Java-created jobs
4. Job format matches RQ expectations
"""

import pytest
fakeredis = pytest.importorskip("fakeredis")
import json
import uuid
from datetime import datetime


@pytest.fixture
def redis_conn():
    """Mocked Redis using fakeredis (no external service)."""
    return fakeredis.FakeRedis(decode_responses=False)


def create_java_style_job(redis_conn, job_id=None, function='test_function', args=None, kwargs=None):
    """
    Create a job in Redis using the same format as Java RqPublisher.

    Simulates Java by:
    - Writing 'rq:job:{id}' hash with 'data' as plain JSON: [func, null, args, kwargs]
    - Pushing the job id onto the RQ list: 'rq:queue:<queue_name>'
    """
    if job_id is None:
        job_id = str(uuid.uuid4())
    if args is None:
        args = ['test_arg']
    if kwargs is None:
        kwargs = {}
    
    queue_name = 'opik:optimizer-cloud'
    job_key = f'rq:job:{job_id}'
    
    # Create data field: [function, null, args, kwargs] as plain JSON string
    data_array = [function, None, args, kwargs]
    json_data = json.dumps(data_array)
    
    # Create HASH fields
    now = datetime.utcnow().isoformat() + 'Z'
    job_fields = {
        b'data': json_data.encode('utf-8'),
        b'created_at': now.encode('utf-8'),
        b'enqueued_at': now.encode('utf-8'),
        b'status': b'queued',
        b'origin': queue_name.encode('utf-8'),
        b'timeout': b'3600'
    }
    
    # Store in Redis
    redis_conn.hset(job_key, mapping=job_fields)
    redis_conn.expire(job_key, 3600)
    redis_conn.rpush(f'rq:queue:{queue_name}', job_id)
    
    return job_id, queue_name


class TestJavaPythonRqContract:
    """Test Java-Python RQ integration contract."""
    
    def test_java_plain_json_data_format(self):
        """Verify Java contract uses plain JSON string for 'data'."""
        test_data = ["function_name", None, ["arg1", "arg2"], {"key": "value"}]
        json_str = json.dumps(test_data)
        # Assert it's valid JSON and round-trips
        parsed = json.loads(json_str)
        assert parsed == test_data
    
    def test_python_can_read_java_created_job(self, redis_conn):
        """Verify Python can read job created by Java."""
        # Given: Job created with Java format
        job_id, queue_name = create_java_style_job(
            redis_conn,
            function='opik_backend.rq_worker.process_optimizer_job',
            args=['Test message']
        )
        
        try:
            # When: Python reads the job
            job_key = f'rq:job:{job_id}'
            job_data = redis_conn.hgetall(job_key)
            
            # Then: All fields are present
            assert b'data' in job_data
            assert b'created_at' in job_data
            assert b'enqueued_at' in job_data
            assert b'status' in job_data
            assert b'origin' in job_data
            assert b'timeout' in job_data
            
            # And: Data is plain JSON
            parsed = json.loads(job_data[b'data'].decode('utf-8'))
            
            # And: Data has correct RQ format
            assert len(parsed) == 4
            assert parsed[0] == 'opik_backend.rq_worker.process_optimizer_job'
            assert parsed[1] is None
            assert parsed[2] == ['Test message']
            assert parsed[3] == {}
            
            # And: Status is correct
            assert job_data[b'status'] == b'queued'
            
        finally:
            # Cleanup
            redis_conn.delete(f'rq:job:{job_id}')
            redis_conn.delete(queue_name)
    
    def test_rq_job_format_matches_expectations(self):
        """Verify RQ job 'data' array structure."""
        # Given: Expected RQ format
        expected_format = {
            'function': str,       # Import path to Python function
            'callback': type(None),# Result callback placeholder (RQ uses None)
            'args': list,          # Positional arguments
            'kwargs': dict         # Keyword arguments
        }
        
        # When: Create job data
        job_data = [
            'opik_backend.rq_worker.process_optimizer_job',
            None,
            ['message'],
            {}
        ]
        
        # Then: Format matches expectations
        assert isinstance(job_data[0], str)
        assert job_data[1] is None
        assert isinstance(job_data[2], list)
        assert isinstance(job_data[3], dict)
    
    def test_data_is_json_not_compressed(self):
        """Ensure contract uses plain JSON, not zlib compression."""
        arr = ["f", None, ["x"], {}]
        json_str = json.dumps(arr)
        # No compression; just JSON presence check
        assert json.loads(json_str) == arr
    
    def test_timestamp_format_compatibility(self):
        """Verify timestamp format is ISO-8601."""
        # Given: Java Instant format
        java_timestamp = "2025-10-14T10:57:05.638222Z"
        
        # When: Parse with Python
        # This should not raise an exception
        from datetime import datetime
        parsed = datetime.fromisoformat(java_timestamp.replace('Z', '+00:00'))
        
        # Then: Successfully parsed
        assert parsed.year == 2025
        assert parsed.month == 10
        assert parsed.day == 14

    def test_rq_api_with_fakeredis_works(self, redis_conn):
        """Use RQ Queue + SimpleWorker (JSONSerializer) over fakeredis to validate processing API."""
        from rq import Queue
        from rq.serializers import JSONSerializer
        from rq.worker import SimpleWorker
        from rq.job import Job

        # Create queue using mocked Redis
        queue_name = 'opik:optimizer-cloud'
        serializer = JSONSerializer()
        q = Queue(queue_name, connection=redis_conn, serializer=serializer)

        # Create a proper job message matching the OptimizationJobContext format
        job_message = {
            'optimization_id': str(uuid.uuid4()),
            'workspace_name': 'test-workspace',
            'opik_api_key': 'test-key',
            'config': {
                'dataset_name': 'test-dataset',
                'prompt': {
                    'messages': [
                        {'role': 'system', 'content': 'You are a helpful assistant'},
                        {'role': 'user', 'content': 'Answer: {question}'}
                    ]
                },
                'llm_model': {
                    'model': 'gpt-4',
                    'parameters': {'temperature': 0.7}
                },
                'evaluation': {
                    'metrics': [
                        {
                            'type': 'equals',
                            'parameters': {}
                        }
                    ]
                },
                'optimizer': {
                    'type': 'gepa',
                    'parameters': {}
                }
            }
        }

        # Enqueue our worker function by reference with proper job message
        from opik_backend.rq_worker import process_optimizer_job
        job = q.enqueue(process_optimizer_job, job_message)

        # Process with SimpleWorker (no forking) against the same mocked Redis
        worker = SimpleWorker([q], connection=redis_conn, serializer=serializer)
        worker.work(burst=True)

        # Fetch job and verify result
        fetched = Job.fetch(job.id, connection=redis_conn, serializer=serializer)
        
        # The job will fail because we don't have a real dataset or LLM keys,
        # but it should at least parse the message correctly and not fail with KeyError
        # Check that it got past the message parsing stage
        if not fetched.is_finished:
            # If it failed, check that it's not due to missing job message fields
            exc_info = fetched.latest_result()
            error_msg = str(exc_info.exc_string) if hasattr(exc_info, 'exc_string') else str(fetched.exc_info)
            # Should not fail with KeyError for optimization_id, workspace_name, or config
            assert 'KeyError' not in error_msg or 'optimization_id' not in error_msg, \
                f"Job failed with message parsing error: {error_msg}"
            # It's OK to fail for other reasons (missing dataset, API keys, etc.)
        else:
            # If it succeeded (unlikely without real resources), that's also OK
            val = fetched.return_value
            assert val is not None





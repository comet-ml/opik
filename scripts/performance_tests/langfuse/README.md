# Performance test - Langfuse

The goal of this performance test is to understand how many traces and spans can be ingested by the Langfuse platform per second.

To keep this test simple, we will run the Langfuse platform locally with the default configuration.

## Install Langfuse locally

You can follow the instructions Langfuse documentation to install Langfuse locally using the docker compose deployment type: [https://langfuse.com/self-hosting/docker-compose](https://langfuse.com/self-hosting/docker-compose)

## Run the test

The test will consist in logging 1000 traces using the Python SDK and measuring two key metrics:
1. Logging time: How long did it take the Python SDK to send the events to the Opik platform ?
2. Dashboard Display time: How long did it take for the traces and spans to be visible in the Opik dashboard ?

To run the test, you can use the following command:

```bash
python performance_test.py --num-traces 1000
```

## Results

We ran the scripts for 2 different configurations:

**Logging 1000 traces**:
```
---------------- Performance results ----------------
Time to log traces and spans           : 11.46 seconds
Time before traces are available in UI : 25.56 seconds
Total time                             : 37.02 seconds
```

**Logging 10,000 traces**:
```
---------------- Performance results ----------------
Time to log traces and spans           : 119.67 seconds
Time before traces are available in UI : 207.49 seconds
Total time                             : 327.15 seconds
```

*Note:* These tests were run on a M3 Macbook Pro using version 3.2.0 of the Langfuse platform.

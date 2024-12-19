# Performance test - Phoenix

The goal of this performance test is to understand how many traces and spans can be ingested by the Phoenix platform per second.

To keep this test simple, we will run the Phoenix platform locally with the default configuration.

## Install Phoenix locally

You can follow the instructions in the Phoenix documentation to install Phoenix locally using the docker deployment type: [https://docs.arize.com/phoenix/deployment/docker#docker](https://docs.arize.com/phoenix/deployment/docker#docker)

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
Time to log traces and spans           : 2.64 seconds
Time before traces are available in UI : 11.10 seconds
Total time                             : 13.74 seconds
```

**Logging 10,000 traces**:
```
---------------- Performance results ----------------
Time to log traces and spans           : 41.00 seconds
Time before traces are available in UI : 128.59 seconds
Total time                             : 169.60 seconds
```

*Note:* These tests were run on a M3 Macbook Pro with version v7.2.0 of the Phoenix platform.

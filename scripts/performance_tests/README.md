# Performance tests

The goal of these tests is to understand how many traces and spans can be ingested by different open-source platforms per second. 

For these tests, we are deploying each platform locally with the default configuration and logging 1,000, 10,000, or 25,000 traces using the Python decorators.

## Test Methodology

Each test measures two key metrics:
1. **Logging time**: How long did it take the Python SDK to send the events to the platform?
2. **Dashboard Display time**: How long did it take for the traces and spans to be visible in the dashboard?

## Results Summary

### Total Time (seconds)

| # Traces | Opik | Langfuse | Phoenix |
|----------|------|----------|---------|
| 1,000    | 3.16 | 9.61     | 13.63   |
| 10,000   | 14.35| 15.03    | 126.49  |
| 25,000   | 34.29| 28.40    | -       |

### Detailed Results

| Platform | # Traces | Time to log traces and spans | Time before traces are available in UI | Total time     |
|----------|----------|------------------------------|----------------------------------------|----------------|
| Opik     | 1,000    | 3.04 seconds                 | 0.12 seconds                           | 3.16 seconds   |
| Phoenix  | 1,000    | 0.28 seconds                 | 13.35 seconds                          | 13.63 seconds  |
| Langfuse | 1,000    | 7.47 seconds                 | 2.15 seconds                           | 9.61 seconds   |
| Opik     | 10,000   | 14.08 seconds                | 0.27 seconds                           | 14.35 seconds  |
| Phoenix  | 10,000   | 2.45 seconds                 | 124.04 seconds                         | 126.49 seconds |
| Langfuse | 10,000   | 12.90 seconds                | 2.13 seconds                           | 15.03 seconds  |
| Opik     | 25,000   | 34.24 seconds                | 0.06 seconds                           | 34.29 seconds  |
| Langfuse | 25,000   | 24.59 seconds                | 3.81 seconds                           | 28.40 seconds  |

*Note: All tests were run on a M3 MacBook Pro with the following platform versions:*
- Opik: v1.3.0
- Phoenix: v7.2.0  
- Langfuse: v3.75.1 OSS

## Running the Tests

To run the performance tests for each platform:

1. Install the platform locally following their respective documentation:
   - [Opik Local Deployment](https://www.comet.com/docs/opik/self-host/local_deployment)
   - [Phoenix Docker Deployment](https://docs.arize.com/phoenix/deployment/docker#docker)
   - [Langfuse Docker Compose](https://langfuse.com/self-hosting/docker-compose)

2. Run the performance test:
   ```bash
   python performance_test.py --num-traces 1000
   ```

For detailed instructions and individual results, see the README files in each platform's directory.

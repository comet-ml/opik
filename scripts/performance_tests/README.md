# Performance tests

The goal of these tests is to understand how many traces and spans can be ingested by different open-source platforms per second. 

For these tests, we are deploying each platform locally with the default configuration and logging either 1,000 or 10,000 traces using the Python decorators.

Results:

| Platform | # Traces | Time to log traces and spans | Time before traces are available in UI | Total time     |
|----------|----------|------------------------------|----------------------------------------|----------------|
| Opik     | 1,000    | 1.60 seconds                 | 0.03 seconds                           | 1.63   seconds |
| Phoenix  | 1,000    | 4.30 seconds                 | 7.90 seconds                           | 12.21 seconds  |
| Langfuse | 1,000    | 14.71 seconds                | 6.79 seconds                           | 21.51 seconds  |
| Opik     | 10,000   | 16.05 seconds                | 0.03 seconds                           | 16.08  seconds |
| Phoenix  | 10,000   | 45.73 seconds                | 85.30 seconds                          | 131.03 seconds |
| Langfuse | 10,000   | 136.05 seconds               | 24.31 seconds                          | 160.36 seconds |

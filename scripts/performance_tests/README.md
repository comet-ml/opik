# Performance tests

The goal of these tests is to understand how many traces and spans can be ingested by different open-source platforms per second. 

For these tests, we are deploying each platform locally with the default configuration and logging either 1,000 or 10,000 traces using the Python decorators.

Results:

| Platform | # Traces | Time to log traces and spans | Time before traces are available in UI | Total time     |
|----------|----------|------------------------------|----------------------------------------|----------------|
| Opik     | 1,000    | 2.53 seconds                 | 0.04 seconds                           | 2.57   seconds |
| Phoenix  | 1,000    | 2.64 seconds                 | 11.10 seconds                          | 13.74 seconds  |
| Langfuse | 1,000    | 11.46 seconds                | 25.56 seconds                          | 37.02 seconds  |
| Opik     | 10,000   | 25.07 seconds                | 0.08 seconds                           | 25.15  seconds |
| Phoenix  | 10,000   | 41.00 seconds                | 128.59 seconds                         | 169.60 seconds |
| Langfuse | 10,000   | 119.67 seconds               | 207.49 seconds                         | 327.15 seconds |

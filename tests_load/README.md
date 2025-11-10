# Load tests

## Install Opik locally

You can follow the Opik documentation to install Opik locally using the docker compose deployment
type: [https://www.comet.com/docs/opik/self-host/local_deployment](https://www.comet.com/docs/opik/self-host/local_deployment)

## Trace and span ingestion test

The goal of this performance test is to understand how many traces and spans can be ingested by the Opik platform per
second.

To keep this test simple, we will run the Opik platform locally with the default configuration.

### Run the test

The test will consist in logging 1000 traces using the Python SDK and measuring two key metrics:

1. Logging time: How long did it take the Python SDK to send the events to the Opik platform?
2. Dashboard Display time: How long did it take for the traces and spans to be visible in the Opik dashboard?

To run the test, you can use the following command:

```bash
python tests/test_trace_span_ingestion.py --num-traces 1000
```

### Results

We ran the scripts for 2 different configurations:

**Logging 1000 traces**:

```
---------------- Performance results ----------------
Time to log traces and spans           : 2.74 seconds
Time before traces are available in UI : 0.57 seconds
Total time                             : 3.31 seconds
```

**Logging 10,000 traces**:

```
---------------- Performance results ----------------
Time to log traces and spans           : 23.10 seconds
Time before traces are available in UI : 0.34 seconds
Total time                             : 23.44 seconds
```

**Note:** These tests were run on a M3 Macbook Pro using version 1.3.0 of the Opik platform.

## Trace and span retrieval test

### Run the test

At least, you should specify the `--start-date` as command line argument. When the `--end-date` is omitted, the traces
and spans from the `--start-date` until the end of that day will be retrieved.

To run the test, you can use the following command:

```bash
python tests/test_trace_span_retrieval.py --project-name performance_test --start-date 2025-03-07
```

Optionally, you can specify the `--end-date` as command line argument, to retrieve the traces and spans for multiple
days.

```bash
python tests/test_trace_span_retrieval.py --project-name performance_test --start-date 2025-03-07 --end-date 2025-03-09
```

## Multimodal with CIFAR-10 sample dataset multi_modal tests

These are sample scripts to help with multi-modal examples:

- [test_image_inference.py](tests/test_image_inference.py): Runs a wide range of image generation inference to
  test online evaluations.
- [test_images_dataset_sample.py](tests/test_images_dataset_sample.py): Loads a sample image dataset for playground and
  experiment testing.

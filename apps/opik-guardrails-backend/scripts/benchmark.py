import httpx
import json
import os
import time
import statistics
import numpy as np
from tqdm import tqdm
from concurrent import futures
from typing import List, Dict, Any

MAX_CONCURRENT_REQUESTS = 4


def send_classification_request(text, topics):
    url = "http://127.02:5000/api/v1/guardrails/validations"
    payload = {
        "text": text,
        "validations": [
            {
                "type": "TOPIC",
                "config": {
                    "topics": topics,
                    "mode": "restrict",
                },
            },
            {
                "type": "PII",
                "config": {
                    "language": "en",
                },
            },
        ],
    }

    start_time = time.time()
    try:
        response = httpx.post(url, json=payload, timeout=20)
        response.raise_for_status()
        result = response.json()
        end_time = time.time()
        latency = end_time - start_time
        result["latency"] = latency
        return result
    except httpx.HTTPStatusError as e:
        end_time = time.time()
        return {
            "error": f"HTTP error occurred: {e.response.status_code} {e.response.text}",
            "latency": end_time - start_time,
        }
    except httpx.RequestError as e:
        end_time = time.time()
        return {
            "error": f"Request error occurred: {str(e)}",
            "latency": end_time - start_time,
        }


def measure_performance(
    text: str, topics: List[str], num_requests: int, max_workers: int
):
    start_time = time.time()
    results = []
    latencies = []
    errors = 0

    with futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        task_futures = [
            executor.submit(send_classification_request, text, topics)
            for _ in range(num_requests)
        ]

        for task in tqdm(
            futures.as_completed(task_futures),
            total=num_requests,
            desc="Sending requests",
        ):
            result = task.result()
            latencies.append(result["latency"])

            if "error" in result:
                errors += 1
                results.append(result["scores"])

    end_time = time.time()
    total_time = end_time - start_time

    # Calculate statistics
    stats = {
        "total_requests": num_requests,
        "successful_requests": num_requests - errors,
        "failed_requests": errors,
        "total_time": total_time,
        "throughput": num_requests / total_time if total_time > 0 else 0,
        "average_speed": total_time / num_requests if num_requests > 0 else 0,
        "concurrent_workers": max_workers,
    }

    # Calculate latency statistics
    if latencies:
        percentiles = [50, 90, 95, 99]
        stats.update(
            {
                "latency": {
                    "min": min(latencies),
                    "max": max(latencies),
                    "mean": statistics.mean(latencies),
                    "median": statistics.median(latencies),
                    "stdev": statistics.stdev(latencies) if len(latencies) > 1 else 0,
                    "percentiles": {
                        f"p{p}": np.percentile(latencies, p) for p in percentiles
                    },
                }
            }
        )

    return results, stats


def print_performance_report(stats: Dict[str, Any]):
    """Print a formatted performance report"""
    print("\n" + "=" * 50)
    print("PERFORMANCE REPORT")
    print("=" * 50)

    print("\nRequest Statistics:")
    print(f"  Total Requests:     {stats['total_requests']}")
    print(f"  Successful:         {stats['successful_requests']}")
    print(f"  Failed:             {stats['failed_requests']}")
    print(
        f"  Success Rate:       {stats['successful_requests']/stats['total_requests']*100:.2f}%"
    )

    print("\nTiming Statistics:")
    print(f"  Total Time:         {stats['total_time']:.2f} seconds")
    print(f"  Throughput:         {stats['throughput']:.2f} requests per second")
    print(f"  Average Speed:      {stats['average_speed']*1000:.2f} ms per request")
    print(f"  Concurrent Workers: {stats['concurrent_workers']}")

    if "latency" in stats:
        print("\nLatency Statistics (seconds):")
        print(f"  Min:               {stats['latency']['min']:.4f}")
        print(f"  Max:               {stats['latency']['max']:.4f}")
        print(f"  Mean:              {stats['latency']['mean']:.4f}")
        print(f"  Median:            {stats['latency']['median']:.4f}")
        print(f"  Std Dev:           {stats['latency']['stdev']:.4f}")
        print("\nLatency Percentiles (seconds):")
        for p, value in stats["latency"]["percentiles"].items():
            print(f"  {p}:                {value:.4f}")

    print("=" * 50)


def run_benchmark(text: str, topics: List[str], num_requests: int, max_workers: int):
    print(
        f"\nRunning benchmark with {num_requests} requests and {max_workers} concurrent workers..."
    )

    # Run a single test request first
    print("\nTesting single request:")
    result = send_classification_request(text, topics)
    if "error" in result:
        print(f"Error in test request: {result['error']}")
        return
    print(json.dumps(result, indent=4))

    # Run the full benchmark
    results, stats = measure_performance(text, topics, num_requests, max_workers)

    # Print the performance report
    print_performance_report(stats)

    return stats


def compare_concurrency_levels(
    text: str, topics: List[str], num_requests: int, concurrency_levels: List[int]
):
    """Compare performance across different concurrency levels"""
    results = {}

    for workers in concurrency_levels:
        print(f"\n\nTesting with {workers} concurrent workers")
        print("-" * 40)
        stats = run_benchmark(text, topics, num_requests, workers)
        results[workers] = stats

    # Print comparison summary
    print("\n\n" + "=" * 60)
    print("CONCURRENCY COMPARISON SUMMARY")
    print("=" * 60)
    print(f"{'Workers':<10} {'Throughput':<15} {'Avg Latency':<15} {'p95 Latency':<15}")
    print("-" * 60)

    for workers, stats in results.items():
        throughput = stats["throughput"]
        avg_latency = stats["latency"]["mean"]
        p95_latency = stats["latency"]["percentiles"]["p95"]
        print(
            f"{workers:<10} {throughput:<15.2f} {avg_latency:<15.4f} {p95_latency:<15.4f}"
        )


if __name__ == "__main__":
    # Get the directory where the script is located
    script_dir = os.path.dirname(os.path.abspath(__file__))

    # Construct the path to financial_article.txt in the same directory
    article_path = os.path.join(script_dir, "financial_article.txt")

    with open(
        article_path,
        mode="rt",
    ) as f:
        text = f.read()
    topics = ["finance", "healthcare", "art", "transport companies"]
    num_requests = 10

    run_benchmark(text, topics, num_requests, max_workers=MAX_CONCURRENT_REQUESTS)

import sys
import time
import statistics
import logging
import os
from typing import List

sys.path.append('src')
from opik_backend.executor_docker import DockerExecutor

# Disable logging to avoid noise in performance measurements
logging.getLogger().setLevel(logging.ERROR)

def measure_execution_time(executor: DockerExecutor, num_tests: int = 15, test_name: str = "Test") -> List[float]:
    """Measure time to execute scoring tasks"""
    times = []
    
    test_code = '''
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

class TestMetric(base_metric.BaseMetric):
    def __init__(self, name: str = "test_metric"):
        super().__init__(name=name, track=False)
    
    def score(self, output: str, reference: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)
'''
    
    test_data = {"output": "test", "reference": "test"}
    
    print(f"🧪 Running {test_name} ({num_tests} requests)...")
    
    for i in range(num_tests):
        start_time = time.time()
        
        try:
            result = executor.run_scoring(test_code, test_data)
            
            end_time = time.time()
            execution_time = (end_time - start_time) * 1000  # Convert to milliseconds
            times.append(execution_time)
            
            # Show progress every 3 tests
            if (i + 1) % 3 == 0:
                print(f"    {i+1}/{num_tests}: {execution_time:.2f}ms")
            
        except Exception as e:
            print(f"  Test {i+1} failed: {e}")
    
    return times

def print_stats(times: List[float], label: str):
    """Print statistics for timing results"""
    if times:
        avg = statistics.mean(times)
        median = statistics.median(times)
        min_time = min(times)
        max_time = max(times)
        
        print(f"\n📈 {label} Results:")
        print(f"   📊 Sample size: {len(times)}")
        print(f"   📏 Average:     {avg:.2f}ms")
        print(f"   📍 Median:      {median:.2f}ms") 
        print(f"   ⬇️  Min:         {min_time:.2f}ms")
        print(f"   ⬆️  Max:         {max_time:.2f}ms")
        
        if len(times) > 1:
            stdev = statistics.stdev(times)
            consistency = stdev / avg * 100
            print(f"   📈 StdDev:      {stdev:.2f}ms")
            print(f"   🎯 Variation:   {consistency:.1f}%")

def run_multiple_rounds():
    """Run multiple rounds to check for consistency"""
    print("🔄 Multiple Round Warmup Comparison")
    print("=" * 60)
    print("🎯 Running 3 rounds of 15 requests each to check consistency")
    
    all_warmup_times = []
    all_no_warmup_times = []
    
    for round_num in range(3):
        print(f"\n{'='*15} ROUND {round_num + 1} {'='*15}")
        
        # Test WITH warmup
        print(f"\n🔥 Round {round_num + 1}: WITH warmup")
        print("-" * 40)
        os.environ["PYTHON_CODE_EXECUTOR_ENABLE_WARMUP"] = "true"
        
        executor_warmup = DockerExecutor()
        try:
            print("⏳ Container pool initialization...")
            time.sleep(4)
            
            warmup_times = measure_execution_time(executor_warmup, 15, f"WITH warmup (Round {round_num + 1})")
            print_stats(warmup_times, f"Round {round_num + 1} WITH warmup")
            all_warmup_times.extend(warmup_times)
            
        finally:
            executor_warmup.cleanup()
            time.sleep(2)
        
        # Test WITHOUT warmup
        print(f"\n⚡ Round {round_num + 1}: WITHOUT warmup")
        print("-" * 40)
        os.environ["PYTHON_CODE_EXECUTOR_ENABLE_WARMUP"] = "false"
        
        executor_no_warmup = DockerExecutor()
        try:
            print("⏳ Container pool initialization...")
            time.sleep(4)
            
            no_warmup_times = measure_execution_time(executor_no_warmup, 15, f"WITHOUT warmup (Round {round_num + 1})")
            print_stats(no_warmup_times, f"Round {round_num + 1} WITHOUT warmup")
            all_no_warmup_times.extend(no_warmup_times)
            
        finally:
            executor_no_warmup.cleanup()
            time.sleep(2)
    
    # Overall analysis
    print("\n" + "=" * 60)
    print("🎯 OVERALL ANALYSIS (3 rounds, 45 requests each)")
    print("=" * 60)
    
    if all_warmup_times and all_no_warmup_times:
        warmup_avg = statistics.mean(all_warmup_times)
        no_warmup_avg = statistics.mean(all_no_warmup_times)
        
        print(f"\n📊 Aggregate Results (45 requests each):")
        print_stats(all_warmup_times, "WITH warmup (TOTAL)")
        print_stats(all_no_warmup_times, "WITHOUT warmup (TOTAL)")
        
        # Calculate difference
        if warmup_avg < no_warmup_avg:
            improvement = ((no_warmup_avg - warmup_avg) / no_warmup_avg) * 100
            faster = "WITH warmup"
        else:
            improvement = ((warmup_avg - no_warmup_avg) / warmup_avg) * 100
            faster = "WITHOUT warmup"
        
        print(f"\n🏆 Final Comparison:")
        print(f"   🔥 WITH warmup average:    {warmup_avg:.2f}ms")
        print(f"   ⚡ WITHOUT warmup average: {no_warmup_avg:.2f}ms")
        print(f"   🏆 Faster configuration:   {faster}")
        print(f"   📈 Performance difference: {improvement:.1f}%")
        
        # Consistency analysis
        warmup_consistency = statistics.stdev(all_warmup_times) / warmup_avg * 100
        no_warmup_consistency = statistics.stdev(all_no_warmup_times) / no_warmup_avg * 100
        
        print(f"\n🎯 Consistency Analysis:")
        print(f"   🔥 WITH warmup variation:    {warmup_consistency:.1f}%")
        print(f"   ⚡ WITHOUT warmup variation: {no_warmup_consistency:.1f}%")
        
        more_consistent = "WITH warmup" if warmup_consistency < no_warmup_consistency else "WITHOUT warmup"
        print(f"   🏆 More consistent:          {more_consistent}")
        
        print(f"\n🔍 Analysis Summary:")
        print(f"   📊 This test used 3 rounds to minimize environmental factors")
        print(f"   🔄 Total of 45 requests per configuration for statistical significance")
        print(f"   �� Results should be more reliable than single-round tests")

if __name__ == "__main__":
    run_multiple_rounds()

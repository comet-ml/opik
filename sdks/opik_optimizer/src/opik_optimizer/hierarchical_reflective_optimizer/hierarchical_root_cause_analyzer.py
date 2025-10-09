import logging
import asyncio
from typing import Any
from tqdm import tqdm

from opik.evaluation.evaluation_result import EvaluationResult
from .types import (
    FailureMode,
    RootCauseAnalysis,
    BatchAnalysis,
    HierarchicalRootCauseAnalysis,
)
from . import reporting

logger = logging.getLogger(__name__)

BATCH_SIZE = 25
MAX_PARALLEL_BATCHES = 5

# Prompt templates
BATCH_ANALYSIS_PROMPT = """You are analyzing evaluation results to identify failure patterns.

TEST RESULTS:
{formatted_batch}

Think through the failures systematically:

1. IDENTIFY: List all distinct types of failures you observe in the test results
2. GROUP: Which failures share similar characteristics or root causes?
3. FREQUENCY: Which patterns appear multiple times across different test cases?
4. PRIORITIZE: Which failures are most critical to address?

Then, for each distinct failure pattern provide:
1. A clear, descriptive name that captures the essence of the failure
2. A comprehensive description of what is failing
3. The underlying root cause explaining why this failure occurs

Focus on patterns that appear multiple times. Be specific about what is failing and why.
Provide a list of failure modes, each with a name, description, and root cause."""

SYNTHESIS_PROMPT = """You are synthesizing root cause analyses from multiple batches of evaluation results.

BATCH ANALYSES:

{batch_summaries}

Your task is to synthesize these batch-level analyses into a unified root cause analysis.

1. MERGE similar failure modes across batches:
   - If multiple batches identify the same or very similar failure pattern, combine them into one unified failure mode
   - Create a comprehensive description that captures the pattern across all relevant batches
   - Identify the core root cause

2. PRIORITIZE the most critical failure modes:
   - Focus on patterns that appear in multiple batches
   - Consider the severity and frequency of each failure
   - Eliminate one-off or minor issues unless they're particularly impactful

3. PROVIDE SYNTHESIS NOTES:
   - Briefly explain which batch-level patterns were merged and why
   - Note any cross-batch trends or patterns
   - Highlight the most critical areas for improvement

Provide:
1. A unified list of failure modes (name, description, root cause)
2. Synthesis notes explaining your analysis process and key findings"""


class HierarchicalRootCauseAnalyzer:
    """
    Performs hierarchical root cause analysis on evaluation results.
    
    This analyzer splits large evaluation datasets into manageable batches,
    performs root cause analysis on each batch in parallel (up to 5 batches 
    concurrently by default), then combines and summarizes the results to 
    identify the most important failure patterns.
    
    Args:
        call_model_fn: Function to call the LLM (should match signature of ReflectiveOptimizer._call_model)
        reasoning_model: Name of the reasoning model to use
        seed: Random seed for reproducibility
        max_parallel_batches: Maximum number of batches to process concurrently (default: 5)
    """
    
    def __init__(
        self, 
        call_model_fn, 
        reasoning_model: str, 
        seed: int,
        max_parallel_batches: int = MAX_PARALLEL_BATCHES,
        verbose: int = 1,
    ):
        """
        Initialize the hierarchical root cause analyzer.
        
        Args:
            call_model_fn: Function to call the LLM (should match signature of ReflectiveOptimizer._call_model)
            reasoning_model: Name of the reasoning model to use
            seed: Random seed for reproducibility
            max_parallel_batches: Maximum number of batches to process concurrently (default: 5)
            verbose: Controls internal logging/progress bars (0=off, 1=on) (default: 1)
        """
        self.call_model_fn = call_model_fn
        self.reasoning_model = reasoning_model
        self.seed = seed
        self.max_parallel_batches = max_parallel_batches
        self.verbose = verbose
    
    def _format_test_results_batch(
        self, 
        test_results: list[Any], 
        batch_start: int,
        batch_end: int,
    ) -> str:
        """
        Format a batch of test results for analysis.
        
        Args:
            test_results: Full list of test results
            batch_start: Starting index of the batch
            batch_end: Ending index of the batch (exclusive)
            
        Returns:
            Formatted string containing test result details
        """
        formatted_results = []
        
        for idx in range(batch_start, min(batch_end, len(test_results))):
            test_result = test_results[idx]
            test_case = test_result.test_case
            dataset_item_id = test_case.dataset_item_id
            
            # Extract scores
            scores_info = []
            for score in test_result.score_results:
                score_str = f"  - {score.name}: {score.value:.3f}"
                if score.reason:
                    score_str += f"\n    Reason: {score.reason}"
                if score.scoring_failed:
                    score_str += " (FAILED)"
                scores_info.append(score_str)
            
            # Format this test result
            result_text = f"""Test Case #{idx + 1} (ID: {dataset_item_id}, Trial: {test_result.trial_id})
Scores:
{chr(10).join(scores_info)}"""
            
            formatted_results.append(result_text)
        
        return "\n\n" + ("="*80 + "\n\n").join(formatted_results)
    
    async def _analyze_batch_async(
        self,
        evaluation_result: EvaluationResult,
        batch_number: int,
        batch_start: int,
        batch_end: int,
    ) -> BatchAnalysis:
        """
        Analyze a single batch of test results asynchronously.
        
        Args:
            evaluation_result: The full evaluation result
            batch_number: The batch number (1-indexed)
            batch_start: Starting index in test_results
            batch_end: Ending index in test_results (exclusive)
            
        Returns:
            BatchAnalysis containing failure modes for this batch
        """
        test_results = evaluation_result.test_results
        actual_end = min(batch_end, len(test_results))
        
        logger.debug(
            f"Analyzing batch {batch_number}: "
            f"test cases {batch_start + 1} to {actual_end}"
        )
        
        formatted_batch = self._format_test_results_batch(
            test_results, batch_start, batch_end
        )
        
        batch_analysis_prompt = BATCH_ANALYSIS_PROMPT.format(
            formatted_batch=formatted_batch,
        )

        root_cause_response = await self.call_model_fn(
            model=self.reasoning_model,
            messages=[{"role": "user", "content": batch_analysis_prompt}],
            seed=self.seed,
            model_kwargs={},
            response_model=RootCauseAnalysis,
        )
        
        return BatchAnalysis(
            batch_number=batch_number,
            start_index=batch_start,
            end_index=actual_end,
            failure_modes=root_cause_response.failure_modes,
        )
    
    async def _synthesize_batch_analyses_async(
        self,
        evaluation_result: EvaluationResult,
        batch_analyses: list[BatchAnalysis],
    ) -> HierarchicalRootCauseAnalysis:
        """
        Synthesize multiple batch analyses into a unified root cause analysis asynchronously.
        
        Args:
            evaluation_result: The full evaluation result
            batch_analyses: List of batch analysis results
            
        Returns:
            HierarchicalRootCauseAnalysis with unified failure modes
        """
        logger.debug(
            f"Synthesizing {len(batch_analyses)} batch analyses "
            f"from {len(evaluation_result.test_results)} total test cases"
        )
        
        # Format all batch analyses for synthesis
        batch_summaries = []
        for batch_analysis in batch_analyses:
            failure_list = []
            for fm in batch_analysis.failure_modes:
                failure_list.append(
                    f"  - {fm.name}\n"
                    f"    Description: {fm.description}\n"
                    f"    Root Cause: {fm.root_cause}"
                )
            
            summary = f"""Batch {batch_analysis.batch_number} (Test Cases {batch_analysis.start_index + 1}-{batch_analysis.end_index}):
{chr(10).join(failure_list)}"""
            batch_summaries.append(summary)
        
        synthesis_prompt = SYNTHESIS_PROMPT.format(
            batch_summaries=chr(10).join(batch_summaries),
        )

        synthesis_response = await self.call_model_fn(
            model=self.reasoning_model,
            messages=[{"role": "user", "content": synthesis_prompt}],
            seed=self.seed,
            model_kwargs={},
            response_model=HierarchicalRootCauseAnalysis,
        )
        
        return synthesis_response
    
    def _validate_reasons_present(self, test_results: list[Any]) -> None:
        """
        Validate that test results include reasons for scoring.
        
        Args:
            test_results: List of test results to validate
            
        Raises:
            ValueError: If no test results have reasons in their score_results
        """
        if not test_results:
            return
        
        has_reasons = False
        for test_result in test_results:
            for score in test_result.score_results:
                if score.reason and score.reason.strip():
                    has_reasons = True
                    break
            if has_reasons:
                break
        
        if not has_reasons:
            raise ValueError(
                "Test results must include 'reason' fields in score_results for hierarchical "
                "root cause analysis to work effectively. Reasons are critical for identifying "
                "failure patterns and root causes. Please ensure your scoring metrics provide "
                "detailed reasons for their scores."
            )
    
    async def analyze_async(
        self, evaluation_result: EvaluationResult
    ) -> HierarchicalRootCauseAnalysis:
        """
        Perform hierarchical root cause analysis on evaluation results asynchronously.
        
        This method:
        1. Validates that test results include reasons (critical for analysis)
        2. Splits test results into batches of BATCH_SIZE
        3. Analyzes batches concurrently (up to max_parallel_batches at once)
        4. Synthesizes batch analyses into unified failure modes
        
        Args:
            evaluation_result: The evaluation result to analyze
            
        Returns:
            HierarchicalRootCauseAnalysis with unified failure modes and synthesis notes
            
        Raises:
            ValueError: If test results don't include reasons, which are critical for analysis
        """
        test_results = evaluation_result.test_results
        num_test_results = len(test_results)
        
        # Validate that reasons are present in test results
        self._validate_reasons_present(test_results)
        
        logger.info(
            f"Starting hierarchical root cause analysis on {num_test_results} test cases"
        )
        
        # Prepare batch tasks
        batch_tasks = []
        batch_number = 1
        for batch_start in range(0, num_test_results, BATCH_SIZE):
            batch_end = min(batch_start + BATCH_SIZE, num_test_results)
            task = self._analyze_batch_async(
                evaluation_result=evaluation_result,
                batch_number=batch_number,
                batch_start=batch_start,
                batch_end=batch_end,
            )
            batch_tasks.append((batch_number, task))
            batch_number += 1
        
        # Process batches with semaphore to limit concurrency
        logger.info(
            f"Processing {len(batch_tasks)} batches concurrently "
            f"(max {self.max_parallel_batches} at once)"
        )
        
        semaphore = asyncio.Semaphore(self.max_parallel_batches)
        
        # Create progress bar for batch processing
        pbar = tqdm(total=len(batch_tasks), desc="Processing batches", unit="batch", leave=False)
        
        async def run_with_semaphore(batch_num, task):
            async with semaphore:
                try:
                    result = await task
                    logger.debug(
                        f"Completed batch {batch_num}: "
                        f"identified {len(result.failure_modes)} failure modes"
                    )
                    pbar.update(1)  # Update progress bar
                    return batch_num, result
                except Exception as exc:
                    logger.error(f"Batch {batch_num} failed: {exc}")
                    pbar.update(1)  # Update progress bar even on error
                    raise
        
        # Run all tasks with semaphore control
        results = await asyncio.gather(
            *[run_with_semaphore(num, task) for num, task in batch_tasks]
        )
        
        pbar.close()  # Close progress bar
        
        # Sort by batch number to maintain order
        batch_analyses = [result for _, result in sorted(results)]
        
        logger.info(
            f"Stage 1 complete: Analyzed {len(batch_analyses)} batches, "
            f"total {sum(len(ba.failure_modes) for ba in batch_analyses)} failure modes"
        )
        
        # Stage 2: Synthesize batch analyses
        logger.info("Stage 2: Synthesizing batch analyses...")
        
        with reporting.display_batch_synthesis(
            num_batches=len(batch_analyses),
            verbose=self.verbose
        ):
            hierarchical_analysis = await self._synthesize_batch_analyses_async(
                evaluation_result=evaluation_result,
                batch_analyses=batch_analyses,
            )
        
        logger.info(
            f"Synthesis complete: "
            f"identified {len(hierarchical_analysis.unified_failure_modes)} unified failure modes"
        )
        
        return hierarchical_analysis
    
    def analyze(self, evaluation_result: EvaluationResult) -> HierarchicalRootCauseAnalysis:
        """
        Synchronous wrapper for analyze_async() for backward compatibility.
        
        Args:
            evaluation_result: The evaluation result to analyze
            
        Returns:
            HierarchicalRootCauseAnalysis with unified failure modes and synthesis notes
        """
        return asyncio.run(self.analyze_async(evaluation_result))


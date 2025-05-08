import os
import time
import logging
from datetime import datetime
from pathlib import Path
from typing import Any

import pandas as pd
import matplotlib.pyplot as plt
# seaborn is imported in benchmark_config.py but not used in OptimizationMonitor
# import seaborn as sns


class OptimizationMonitor:
    """Monitor optimization progress and generate plots."""

    def __init__(self, output_dir: str):
        self.output_dir = output_dir
        self.metrics_history = []
        self.prompts_history = []
        self.start_time = time.time()

        # Create output directory if it doesn't exist
        os.makedirs(output_dir, exist_ok=True)

        # Get a logger instance for this class or use a global one if already defined
        # Assuming a global logger might not be set up in benchmark_config.py in the same way as run_benchmark.py
        # So, creating one specifically for this class or using a passed one would be robust.
        # For now, let's get one based on the module name.
        self.logger = logging.getLogger(__name__)

    def callback(self, result: Any, step: int, total_steps: int) -> None:
        """Callback function called after each optimization step."""
        timestamp = datetime.now().isoformat()

        # Extract metrics from result
        metrics = {}
        if hasattr(result, "scores") and result.scores is not None:
            metrics = result.scores
        elif hasattr(result, "score") and result.score is not None:
            metrics = {"score": result.score}
        # If result is an OptimizationResult object, it might have score directly
        elif isinstance(result, dict) and "score" in result and result["score"] is not None:
             metrics = {"score": result["score"]}
        elif hasattr(result, "metric_name") and hasattr(result, "score") and result.score is not None: # For OptimizationResult like objects
            metrics = {result.metric_name if result.metric_name else "score": result.score}

        # Record metrics
        metrics_entry = {
            "timestamp": timestamp,
            "step": step,
            "total_steps": total_steps,
            "trial": len(self.metrics_history) + 1,
            **metrics # score will be here if found
        }
        self.metrics_history.append(metrics_entry)

        current_prompt_str = "N/A"
        # Record prompt if available
        if hasattr(result, "prompt") and result.prompt is not None:
            current_prompt_str = str(result.prompt)
            prompt_entry = {
                "timestamp": timestamp,
                "step": step,
                "trial": len(self.prompts_history) + 1,
                "prompt": current_prompt_str
            }
            self.prompts_history.append(prompt_entry)
        elif isinstance(result, dict) and result.get("prompt") is not None:
            current_prompt_str = str(result.get("prompt"))
            # (Potentially add to prompts_history here too if this case is common)

        # Log progress
        self.logger.info(f"\nTrial [bold cyan]{metrics_entry['trial']}[/bold cyan] - Step [cyan]{step}/{total_steps}[/cyan]")

        # Format metrics for logging
        if metrics:
            metrics_log_str = ", ".join([f"[italic]{k}[/italic]: {v:.4f}" if isinstance(v, float) else f"[italic]{k}[/italic]: {v}" for k,v in metrics.items()])
            self.logger.info(f"  Metrics: {metrics_log_str}")
        else:
            self.logger.info("  Metrics: [dim]N/A[/dim]")

        if current_prompt_str != "N/A":
            self.logger.info(f"  Current prompt: [italic]{current_prompt_str[:150]}...[/italic]")
        else:
            self.logger.info("  Current prompt: [dim]N/A[/dim]")

    def save_progress(self) -> None:
        """Save optimization progress to files."""
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

        # Save metrics history
        if self.metrics_history:
            metrics_df = pd.DataFrame(self.metrics_history)
            metrics_file = os.path.join(self.output_dir, f"metrics_{timestamp}.csv")
            metrics_df.to_csv(metrics_file, index=False)

        # Save prompts history
        if self.prompts_history:
            prompts_df = pd.DataFrame(self.prompts_history)
            prompts_file = os.path.join(self.output_dir, f"prompts_{timestamp}.csv")
            prompts_df.to_csv(prompts_file, index=False)

        # Generate plots
        self.generate_plots(timestamp)

    def generate_plots(self, timestamp: str) -> None:
        """Generate plots from optimization history."""
        if not self.metrics_history:
            return

        metrics_df = pd.DataFrame(self.metrics_history)

        # Plot metrics over trials
        plt.figure(figsize=(12, 6))
        for metric in metrics_df.columns:
            if metric not in ["timestamp", "step", "total_steps", "trial"]:
                plt.plot(metrics_df["trial"], metrics_df[metric], label=metric)

        plt.xlabel("Trial")
        plt.ylabel("Score")
        plt.title("Optimization Progress")
        plt.legend()
        plt.grid(True)

        # Save plot
        plot_file = os.path.join(self.output_dir, f"optimization_progress_{timestamp}.png")
        plt.savefig(plot_file)
        plt.close()

        # Plot metrics over time
        plt.figure(figsize=(12, 6))
        metrics_df["timestamp"] = pd.to_datetime(metrics_df["timestamp"])
        for metric in metrics_df.columns:
            if metric not in ["timestamp", "step", "total_steps", "trial"]:
                plt.plot(metrics_df["timestamp"], metrics_df[metric], label=metric)

        plt.xlabel("Time")
        plt.ylabel("Score")
        plt.title("Optimization Progress Over Time")
        plt.legend()
        plt.grid(True)
        plt.xticks(rotation=45)

        # Save plot
        time_plot_file = os.path.join(self.output_dir, f"optimization_progress_time_{timestamp}.png")
        plt.savefig(time_plot_file, bbox_inches="tight")
        plt.close()


def get_optimization_monitor(
    output_dir: str = "benchmark_results",
) -> OptimizationMonitor:
    """Get an instance of the optimization monitor."""
    return OptimizationMonitor(output_dir) 
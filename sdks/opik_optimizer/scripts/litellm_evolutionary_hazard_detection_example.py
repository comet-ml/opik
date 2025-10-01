"""
Multimodal Driving Hazard Detection Example using Evolutionary Optimizer.

This example demonstrates:
1. Loading the DHPR dataset with images encoded as base64
2. Creating a multimodal prompt with structured content (text + images)
3. Using a vision-capable model (GPT-4o-mini) for hazard detection
4. Evaluating with a custom LLM-as-a-Judge metric
5. Optimizing the prompt with the Evolutionary Optimizer

The optimizer will evolve prompts to improve hazard detection accuracy
while preserving the image inputs throughout the evolutionary process.
"""

from typing import Any
import os
import sys
import logging

from opik_optimizer import EvolutionaryOptimizer, ChatPrompt
from opik_optimizer.datasets import driving_hazard_50
from opik_optimizer.metrics import MultimodalLLMJudge

from opik.evaluation.metrics.score_result import ScoreResult


# ============================================================================
# CONFIGURATION - Adjust these settings for your needs
# ============================================================================

# Optimization settings (smaller values = faster, fewer API calls, less context usage)
POPULATION_SIZE = 10  # Number of prompt variations per generation
NUM_GENERATIONS = 5  # Number of evolutionary iterations
N_SAMPLES = 5  # Number of dataset samples to use for optimization

# Image settings - Optimized for GPT-5's 400k context
# Adjust these when loading the dataset below
MAX_IMAGE_SIZE = (640, 480)  # Width, height in pixels. Options: (400,300) small, (512,384) medium, (640,480) large
IMAGE_QUALITY = 75  # JPEG quality 1-100. Options: 40-50 (small), 60-70 (balanced), 85+ (high)
# Note: (640x480, quality=75) gives ~25-35k tokens per image, fits well in 400k context
# If using GPT-4o (128k context), reduce to: MAX_IMAGE_SIZE = (512, 384), IMAGE_QUALITY = 60

# Model settings - Training cheaper model with better judge
# Using GPT-5 models (400k context)

# PRIMARY: GPT-5 models (400k context)
VISION_MODEL = "gpt-5-nano"  # Vision model being optimized (fast, cheap, 400k context)
JUDGE_MODEL = "gpt-5"  # Evaluation model (powerful judge with reasoning, 400k context)

# FALLBACK: If GPT-5 is not available via your API provider, use GPT-4o:
# VISION_MODEL = "gpt-4o-mini"  # 128k context
# JUDGE_MODEL = "gpt-4o"  # 128k context
# Then reduce image quality: MAX_IMAGE_SIZE = (512, 384), IMAGE_QUALITY = 60

# ALTERNATIVE: Via OpenRouter (if you have GPT-5 access there)
# VISION_MODEL = "openrouter/openai/gpt-5-nano"
# JUDGE_MODEL = "openrouter/openai/gpt-5"
# export OPENROUTER_API_KEY="your-key"

# ============================================================================


# Load the driving hazard dataset with images
# Each item contains:
# - question: Text question about the image
# - image_content: Structured content with text and base64-encoded image
# - hazard: Expected hazard description (ground truth)
# - question_id: Unique identifier
dataset = driving_hazard_50(
    test_mode=True,  # Use test_mode=True for quick testing (5 samples)
    max_image_size=MAX_IMAGE_SIZE,  # Resize images to reduce token usage
    image_quality=IMAGE_QUALITY,  # JPEG compression quality
)


def multimodal_hazard_judge(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """
    Custom evaluation metric using LLM-as-a-Judge with vision support.

    Compares the model's hazard detection output with the expected hazard
    description while considering the input image.

    Args:
        dataset_item: Dataset item with image_content and expected hazard
        llm_output: The model's hazard detection output

    Returns:
        ScoreResult with match score (0.0-1.0) and reasoning
    """
    metric = MultimodalLLMJudge(
        model=JUDGE_MODEL,  # Vision-capable judge model
        evaluation_criteria="""
Evaluate hazard detection with strict requirements:

1. **Accuracy (40%)**: Did it identify the EXACT hazard type?
   - Pedestrian vs vehicle vs obstacle specificity
   - Correct location (left/right/center/ahead)
   - Distance estimation if applicable

2. **Completeness (30%)**: All hazards mentioned?
   - Primary hazard (MUST catch this)
   - Secondary hazards (bonus points)
   - Environmental factors (weather, lighting, road conditions)

3. **Actionability (20%)**: Clear driver guidance?
   - Urgency level (immediate/moderate/low)
   - Recommended action (brake/slow/monitor/stop)
   - Timing (now vs approaching)

4. **Visual Understanding (10%)**: Correct image interpretation?
   - Scene context (highway, city, parking, intersection)
   - Traffic flow understanding
   - Spatial relationships between objects

**Scoring Guidelines:**
- Score 1.0: ALL critical hazards identified with precise locations and actionable details
- Score 0.7-0.9: Primary hazard caught with good details, minor omissions acceptable
- Score 0.5-0.6: Partial detection but missing key details or secondary hazards
- Score below 0.5: Missed critical hazards or major inaccuracies

Consider semantically equivalent descriptions as correct matches, but reward specificity.
""",
    )

    # Get the multimodal input (with image)
    image_content = dataset_item.get("image_content", dataset_item.get("question", ""))

    # Get the expected hazard description
    expected_hazard = dataset_item.get("hazard", "")

    return metric.score(
        input=image_content,
        output=llm_output,
        expected_output=expected_hazard,
    )


# Create multimodal prompt for hazard detection
# The {image_content} placeholder will be replaced with structured content
# that includes both the question text and the base64-encoded dashcam image
system_prompt = """You are an expert driving safety assistant specialized in hazard detection.

Your task is to analyze dashcam images and identify potential hazards that a driver should be aware of.

For each image:
1. Carefully examine the visual scene
2. Identify any potential hazards (pedestrians, vehicles, road conditions, obstacles, etc.)
3. Assess the urgency and severity of each hazard
4. Provide a clear, specific description of the hazard

Be precise and actionable in your hazard descriptions. Focus on safety-critical information."""

# Using messages format to support structured content with images
# The dataset items have 'image_content' which is structured content
# in OpenAI format: [{"type": "text", "text": "..."}, {"type": "image_url", ...}]
prompt = ChatPrompt(
    messages=[
        {"role": "system", "content": system_prompt},
        {
            "role": "user",
            "content": "{image_content}",  # Will be replaced with structured content + image
        },
    ],
)

# Initialize the Evolutionary Optimizer with multimodal support
# The optimizer will:
# - Mutate the text prompts while preserving images
# - Use crossover to combine effective prompt strategies
# - Evaluate using the vision-capable LLM judge
# - Evolve towards better hazard detection prompts
optimizer = EvolutionaryOptimizer(
    model=VISION_MODEL,  # Vision-capable model
    population_size=POPULATION_SIZE,  # Smaller population for multimodal (images are large)
    num_generations=NUM_GENERATIONS,  # Fewer generations to reduce API calls
    enable_moo=False,  # Single objective optimization
    enable_llm_crossover=False,  # Disable LLM crossover to reduce API calls with large images
    infer_output_style=False,  # IMPORTANT: Disable for multimodal to avoid context overflow
    verbose=1,  # Show progress
)

print("=" * 80)
print("MULTIMODAL EVOLUTIONARY OPTIMIZER - DRIVING HAZARD DETECTION")
print("=" * 80)
print(f"\nDataset: {len(dataset.get_items())} driving scenarios with images")
print(f"Model: {optimizer.model} (vision-capable)")
print(f"Population size: {optimizer.population_size}")
print(f"Generations: {optimizer.num_generations}")
print(f"Evaluation: Multimodal LLM-as-a-Judge ({JUDGE_MODEL})")
print(f"\nNOTE: Images are resized to 512x384 and compressed to reduce token usage")
print(f"NOTE: Using {N_SAMPLES} samples to avoid context window limits with base64 images")
print(f"TIP: Adjust POPULATION_SIZE, NUM_GENERATIONS, N_SAMPLES at the top of this script")
print("\n" + "=" * 80 + "\n")

# Optimize the prompt
# The optimizer will evolve prompts to maximize the match score
# between the model's hazard detection and the ground truth hazards
#
# Note: If you encounter network errors, the optimization will automatically
# retry failed operations. Just re-run the script if it fails completely.
try:
    optimization_result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=multimodal_hazard_judge,
        n_samples=N_SAMPLES,  # Use fewer samples to avoid context overflow with images
    )
except Exception as e:
    print(f"\n❌ Optimization failed with error: {e}")
    print("\n💡 This is often a transient network error. Try:")
    print("   1. Re-run the script (usually works)")
    print("   2. Check your internet connection")
    print("   3. Check Opik API status")
    print("\n   The optimization was making progress - your last best score was shown above!")
    raise

print("\n" + "=" * 80)
print("OPTIMIZATION COMPLETE")
print("=" * 80 + "\n")

# Display the optimization results
optimization_result.display()

print("\n" + "=" * 80)
print("BEST PROMPT")
print("=" * 80)
print(optimization_result.prompt)
print("\n" + "=" * 80)

# Example of how to use the optimized prompt with new images:
print("\nTo use the optimized prompt with new driving images:")
print("1. Load a new dashcam image")
print("2. Encode it to base64 using image_helpers.encode_file_to_base64_uri()")
print("3. Create structured content with convert_to_structured_content()")
print("4. Pass it to the optimized prompt")
print("\nExample:")
print("""
from opik_optimizer.utils.image_helpers import (
    encode_file_to_base64_uri,
    convert_to_structured_content,
)

# Load and encode image with YOUR preferred settings
image_uri = encode_file_to_base64_uri(
    "dashcam.jpg",
    max_size=(512, 384),  # Match training size
)

# Create structured content
image_content = convert_to_structured_content(
    text="Identify any driving hazards in this image.",
    image_uri=image_uri,
)

# Create ChatPrompt from optimized messages and substitute placeholders
optimized_prompt = ChatPrompt(messages=optimization_result.prompt)
messages = optimized_prompt.get_messages(
    dataset_item={"image_content": image_content}
)
""")

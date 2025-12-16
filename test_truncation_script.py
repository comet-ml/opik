"""
Test script to create a dataset with items containing 20,000 characters and base64 images.
This helps verify the truncation fix in the experiment items sidebar.

Usage:
    python test_truncation_script.py

Prerequisites:
    pip install opik pillow
    Set OPIK_API_KEY and OPIK_WORKSPACE environment variables (or use local Opik)
"""

import base64
import io
import datetime

from opik import Opik
from opik.evaluation import evaluate

# Initialize client
client = Opik()

# Generate 20,000 character strings
LONG_TEXT_1 = "A" * 10000 + "B" * 10000  # 20,000 chars: 10k A's followed by 10k B's
LONG_TEXT_2 = "X" * 10000 + "Y" * 10000  # 20,000 chars: 10k X's followed by 10k Y's


def generate_test_image_base64(width=100, height=100, color=(255, 0, 0)):
    """Generate a simple colored PNG image and return as base64 string."""
    try:
        from PIL import Image
        
        # Create a simple colored image
        img = Image.new("RGB", (width, height), color)
        
        # Save to bytes
        buffer = io.BytesIO()
        img.save(buffer, format="PNG")
        buffer.seek(0)
        
        # Encode to base64
        return base64.b64encode(buffer.read()).decode("utf-8")
    except ImportError:
        # Fallback: use a minimal valid PNG (1x1 red pixel)
        # This is a valid PNG file for a 1x1 red pixel
        minimal_png = (
            b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01'
            b'\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00'
            b'\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x00\x03'
            b'\x00\x01\x00\x05\xfe\xd4\x00\x00\x00\x00IEND\xaeB`\x82'
        )
        return base64.b64encode(minimal_png).decode("utf-8")


# Generate base64 images
RED_IMAGE_BASE64 = generate_test_image_base64(200, 200, (255, 0, 0))  # Red image
BLUE_IMAGE_BASE64 = generate_test_image_base64(200, 200, (0, 0, 255))  # Blue image

print(f"Generated test images:")
print(f"  - Red image: {len(RED_IMAGE_BASE64)} characters")
print(f"  - Blue image: {len(BLUE_IMAGE_BASE64)} characters")

# Create dataset name with timestamp for uniqueness
timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
DATASET_NAME = f"truncation_test_dataset_{timestamp}"

print(f"\nCreating dataset: {DATASET_NAME}")

# Create dataset with 2 items containing long text and images
dataset = client.get_or_create_dataset(name=DATASET_NAME)

# Add items with 20,000 character fields AND base64 images
dataset.insert([
    {
        "input": f"Test input 1 with long content: {LONG_TEXT_1}",
        "expected_output": f"Expected output 1 with long content: {LONG_TEXT_1}",
        "image": f"data:image/png;base64,{RED_IMAGE_BASE64}",
        "raw_image_base64": RED_IMAGE_BASE64,  # Without data URI prefix
    },
    {
        "input": f"Test input 2 with long content: {LONG_TEXT_2}",
        "expected_output": f"Expected output 2 with long content: {LONG_TEXT_2}",
        "image": f"data:image/png;base64,{BLUE_IMAGE_BASE64}",
        "raw_image_base64": BLUE_IMAGE_BASE64,  # Without data URI prefix
    },
])

print(f"Dataset created with 2 items containing:")
print(f"  - 20,000+ character text fields")
print(f"  - Base64 encoded PNG images")


# Define a simple task function that returns long output with images
def evaluation_task(item: dict) -> dict:
    # Return a long output with image to test truncation in experiment items
    input_text = item.get("input", "")
    image = item.get("image", "")
    raw_image = item.get("raw_image_base64", "")
    
    # Return output as a dict with text and images embedded
    # This becomes the trace output which shows in experiment items
    return {
        "output": {
            "text": f"Processed output with 20k chars: {input_text[:20000]}",
            "image_with_prefix": image,  # data:image/png;base64,...
            "image_raw_base64": raw_image,  # Just the base64 string (starts with iVBORw0KGgo for PNG)
            "summary": "This output contains both long text and base64 images to test truncation",
        }
    }


# Run evaluation to create an experiment
print(f"\nRunning evaluation to create experiment...")

result = evaluate(
    dataset=dataset,
    task=evaluation_task,
    experiment_name=f"truncation_test_experiment_{timestamp}",
    scoring_metrics=[],  # No metrics needed for this test
)

print(f"\n✅ Test setup complete!")
print(f"\nTo test the truncation fix:")
print(f"1. Go to Opik UI")
print(f"2. Navigate to Datasets > {DATASET_NAME}")
print(f"3. Click 'Compare experiments'")
print(f"4. In the TABLE, you should see:")
print(f"   - Text truncated to ~10,000 chars")
print(f'   - Images replaced with "[image]"')
print(f"5. Click on a row to open the SIDEBAR")
print(f"6. In the SIDEBAR, you should see:")
print(f"   - FULL text (20,000 chars)")
print(f"   - FULL base64 image data (not replaced with [image])")

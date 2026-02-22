"""
Example: OpenAI Text-to-Speech (TTS) Tracking with Opik

This example demonstrates how to track OpenAI TTS API calls with Opik,
including character-based usage tracking for cost calculation.

TTS Pricing (as of 2025):
- tts-1: $0.015 per 1,000 characters
- tts-1-hd: $0.030 per 1,000 characters
"""

import openai
import opik
from opik.integrations.openai import track_openai

# Configure Opik (optional - uses environment variables by default)
# opik.configure(api_key="your-api-key")

# Create and track OpenAI client
client = openai.OpenAI()
client = track_openai(client, project_name="tts-demo")


def basic_tts_example():
    """Basic TTS example with tts-1 model."""
    print("=== Basic TTS Example ===")
    
    text = "Hello! This is a demonstration of OpenAI's text to speech capabilities."
    
    # Generate speech
    response = client.audio.speech.create(
        model="tts-1",
        voice="alloy",
        input=text
    )
    
    # Save audio file
    with open("output_basic.mp3", "wb") as f:
        f.write(response.content)
    
    print(f"✓ Generated speech for {len(text)} characters")
    print(f"  Estimated cost: ${(len(text) / 1000) * 0.015:.6f}")
    print(f"  Audio saved to: output_basic.mp3")


def hd_tts_example():
    """High-definition TTS example with tts-1-hd model."""
    print("\n=== HD TTS Example ===")
    
    text = "This is high-definition audio with enhanced clarity and quality."
    
    # Generate HD speech
    response = client.audio.speech.create(
        model="tts-1-hd",
        voice="nova",
        input=text,
        speed=1.0
    )
    
    # Save audio file
    with open("output_hd.mp3", "wb") as f:
        f.write(response.content)
    
    print(f"✓ Generated HD speech for {len(text)} characters")
    print(f"  Estimated cost: ${(len(text) / 1000) * 0.030:.6f}")
    print(f"  Audio saved to: output_hd.mp3")


def multi_voice_example():
    """Example using different voices."""
    print("\n=== Multi-Voice Example ===")
    
    voices = ["alloy", "echo", "fable", "onyx", "nova", "shimmer"]
    text = "Testing different voice options."
    
    for voice in voices:
        response = client.audio.speech.create(
            model="tts-1",
            voice=voice,
            input=text
        )
        
        filename = f"output_{voice}.mp3"
        with open(filename, "wb") as f:
            f.write(response.content)
        
        print(f"✓ Generated speech with voice '{voice}' -> {filename}")
    
    total_chars = len(text) * len(voices)
    print(f"\nTotal characters: {total_chars}")
    print(f"Total estimated cost: ${(total_chars / 1000) * 0.015:.6f}")


def streaming_tts_example():
    """Example with streaming response."""
    print("\n=== Streaming TTS Example ===")
    
    text = "This example demonstrates streaming audio generation."
    
    # Generate speech with streaming
    response = client.audio.speech.create(
        model="tts-1",
        voice="alloy",
        input=text,
        response_format="opus"  # Opus format for streaming
    )
    
    # Save streamed audio
    with open("output_stream.opus", "wb") as f:
        f.write(response.content)
    
    print(f"✓ Streamed speech for {len(text)} characters")
    print(f"  Audio saved to: output_stream.opus")


def cost_tracking_example():
    """Example showing cost tracking for multiple TTS calls."""
    print("\n=== Cost Tracking Example ===")
    
    texts = [
        "First audio segment.",
        "Second audio segment with more content to demonstrate cost tracking.",
        "Third segment.",
    ]
    
    total_chars = 0
    
    for i, text in enumerate(texts, 1):
        response = client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input=text
        )
        
        total_chars += len(text)
        print(f"  Segment {i}: {len(text)} characters")
    
    print(f"\nTotal characters processed: {total_chars}")
    print(f"Total estimated cost: ${(total_chars / 1000) * 0.015:.6f}")
    print("\n✓ All usage data tracked in Opik dashboard")


if __name__ == "__main__":
    print("OpenAI TTS Tracking with Opik\n")
    print("This example will generate several audio files and track usage in Opik.")
    print("Make sure you have OPENAI_API_KEY set in your environment.\n")
    
    try:
        # Run examples
        basic_tts_example()
        hd_tts_example()
        multi_voice_example()
        streaming_tts_example()
        cost_tracking_example()
        
        # Flush tracking data to Opik
        opik.flush_tracker()
        
        print("\n" + "="*60)
        print("✓ All examples completed successfully!")
        print("✓ Usage data has been sent to Opik")
        print("  View your traces at: https://www.comet.com/opik")
        print("="*60)
        
    except Exception as e:
        print(f"\n❌ Error: {e}")
        print("Make sure your OPENAI_API_KEY is set correctly.")

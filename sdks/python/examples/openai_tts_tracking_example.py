#!/usr/bin/env python3
"""
OpenAI TTS Tracking Example

This example demonstrates how to use Opik to track OpenAI Text-to-Speech (TTS) calls,
including character-based usage tracking and cost estimation.

Requirements:
- OpenAI API key set as OPENAI_API_KEY environment variable
- Opik configured (set OPIK_URL_OVERRIDE if using local instance)
"""

import os
import opik
from opik.integrations.openai import track_openai
import openai

# Example texts for TTS
EXAMPLE_TEXTS = [
    "Hello, world! This is a simple TTS example.",
    "The quick brown fox jumps over the lazy dog. This sentence contains 43 characters.",
    "OpenAI's Text-to-Speech API supports multiple voices including alloy, echo, fable, onyx, nova, and shimmer.",
]

def main():
    # Initialize OpenAI client
    client = openai.OpenAI()
    
    # Track OpenAI calls with Opik
    client = track_openai(client, project_name="tts-tracking-demo")
    
    print("🎵 OpenAI TTS Tracking Demo")
    print("=" * 50)
    
    for i, text in enumerate(EXAMPLE_TEXTS, 1):
        print(f"\n📝 Example {i}: {text}")
        print(f"📊 Character count: {len(text)}")
        
        try:
            # Test regular TTS call
            print("🔊 Generating speech with regular API...")
            response = client.audio.speech.create(
                model="tts-1",
                voice="alloy",
                input=text,
                response_format="mp3"
            )
            
            print(f"✅ Generated audio: {len(response.content)} bytes")
            
            # Test streaming TTS call (recommended approach)
            print("🌊 Generating speech with streaming API...")
            with client.audio.speech.with_streaming_response.create(
                model="tts-1-hd",  # Higher quality model
                voice="nova",
                input=text,
                response_format="mp3",
                speed=1.1
            ) as stream_response:
                # Save to file (you could also stream to audio player)
                filename = f"/tmp/tts_example_{i}.mp3"
                stream_response.stream_to_file(filename)
                
            print(f"✅ Streamed audio saved to: {filename}")
            
        except Exception as e:
            print(f"❌ Error: {e}")
            print("💡 Make sure your OPENAI_API_KEY is set correctly")
    
    print("\n🎯 Usage Tracking")
    print("=" * 50)
    print("✅ All TTS calls have been tracked by Opik!")
    print("📈 You can view the following metrics in your Opik dashboard:")
    print("   • Character count (input_characters)")
    print("   • Token usage (mapped from characters for consistency)")
    print("   • Cost estimation based on character count")
    print("   • Model and voice information")
    print("   • Response metadata (format, speed, audio size)")
    print("   • Performance metrics (duration, etc.)")
    
    # Show expected cost calculation
    total_chars = sum(len(text) for text in EXAMPLE_TEXTS)
    tts1_cost = total_chars * 0.000015  # $0.000015 per character
    tts1hd_cost = total_chars * 0.00003  # $0.00003 per character
    
    print(f"\n💰 Estimated Costs:")
    print(f"   • TTS-1 calls: ${tts1_cost:.6f} ({total_chars} chars × $0.000015)")
    print(f"   • TTS-1-HD calls: ${tts1hd_cost:.6f} ({total_chars} chars × $0.000030)")
    print(f"   • Total estimated cost: ${(tts1_cost + tts1hd_cost):.6f}")

if __name__ == "__main__":
    if not os.getenv("OPENAI_API_KEY"):
        print("❌ Error: OPENAI_API_KEY environment variable not set")
        print("💡 Set your OpenAI API key: export OPENAI_API_KEY='your-key-here'")
        exit(1)
    
    main()
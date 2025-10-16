"""
Comprehensive multimodal image-generation test suite for Opik across providers.

What this script tests (single default prompt applied everywhere):
- OpenAI DALL·E 3 via Images API (images.generate)
- OpenAI gpt-image-1 via Images API (images.generate)
- OpenRouter Gemini 2.5 Flash Image via chat.completions (modalities=["image","text"]) and data URL extraction
- Google Gemini (GenAI):
  - Native: generate_content(model="gemini-2.5-flash-image-preview") → inline image bytes
  - Fallback: Imagen generate_images(model="imagen-3.0-generate-002") → image bytes/URI
- (Detection only) Google ADK is noted for agents, but images are produced through Google GenAI
- OpenAI Agents: image generation is expected via a tool that calls DALL·E

Environment variables:
- OPENAI_API_KEY            # OpenAI (DALL·E 3, gpt-image-1, Agents)
- OPENROUTER_API_KEY        # OpenRouter (Gemini 2.5 Flash Image)
- GOOGLE_API_KEY or GEMINI_API_KEY  # Google GenAI/Gemini

Notes:
- OpenRouter returns image as a base64 data URL; we extract it and log to Opik
- For Google, we prefer Gemini native image generation where available, otherwise Imagen
- All successful generations log input/output/metadata to Opik for later evaluation

Usage:
    export OPENAI_API_KEY="sk-..."
    export OPENROUTER_API_KEY="sk-or-..."     # optional
    export GOOGLE_API_KEY="..."               # or GEMINI_API_KEY
    python test_image_inference.py

    # Optional: provide a custom prompt
    python test_image_inference.py "give me an image of an orange and white owl perched on a tree in a canyon, photorealistic wide angle shot 35mm"
"""

import base64
import json
import os
import time

import opik
from openai import OpenAI
from opik.integrations.anthropic import track_anthropic
from opik.integrations.openai import track_openai


# Generic helper to robustly extract image URL from mixed SDK responses
def _extract_image_url(value):
    try:
        # Dict form
        if isinstance(value, dict):
            if "image_url" in value:
                url_val = value["image_url"]
                if isinstance(url_val, dict) and "url" in url_val:
                    return url_val["url"]
                if isinstance(url_val, str):
                    return url_val
            for v in value.values():
                u = _extract_image_url(v)
                if u:
                    return u
            return None
        # List form
        if isinstance(value, list):
            for item in value:
                u = _extract_image_url(item)
                if u:
                    return u
            return None
        # Object with attributes
        if hasattr(value, "__dict__"):
            return _extract_image_url(vars(value))
        return None
    except Exception:
        return None


# Optional imports for other providers
try:
    import anthropic

    ANTHROPIC_AVAILABLE = True
except ImportError:
    ANTHROPIC_AVAILABLE = False
    print("⚠️  Anthropic not available. Install with: pip install anthropic")

try:
    import google.adk

    GOOGLE_ADK_AVAILABLE = True
except ImportError:
    GOOGLE_ADK_AVAILABLE = False
    print("⚠️  Google ADK not available. Install with: pip install google-adk")

try:
    from agents import Agent, Runner, function_tool, set_trace_processors
    from opik.integrations.openai.agents import OpikTracingProcessor

    OPENAI_AGENTS_AVAILABLE = True
except ImportError:
    OPENAI_AGENTS_AVAILABLE = False
    print("⚠️  OpenAI Agents not available. Install with: pip install openai-agents")

PROJECT_NAME = "opik_multimodal_test"

# Default prompt for image generation
DEFAULT_PROMPT = "give me an image of an orange and white owl perched on a tree in a canyon, photorealistic wide angle shot 35mm"


# Initialize clients for different providers
def initialize_clients():
    """Initialize and track clients for all available providers"""
    clients = {}

    # OpenAI client
    if os.environ.get("OPENAI_API_KEY"):
        openai_client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))
        clients["openai"] = track_openai(openai_client, project_name=PROJECT_NAME)
        print("✅ OpenAI client initialized")
    else:
        print("⚠️  OPENAI_API_KEY not set")

    # Anthropic client
    if ANTHROPIC_AVAILABLE and os.environ.get("ANTHROPIC_API_KEY"):
        anthropic_client = anthropic.Anthropic(api_key=os.environ.get("ANTHROPIC_API_KEY"))
        clients["anthropic"] = track_anthropic(anthropic_client, project_name=PROJECT_NAME)
        print("✅ Anthropic client initialized")
    else:
        print("⚠️  Anthropic client not available")

    # OpenRouter client (using OpenAI SDK)
    if os.environ.get("OPENROUTER_API_KEY"):
        try:
            openrouter_client = OpenAI(
                base_url="https://openrouter.ai/api/v1",
                api_key=os.environ.get("OPENROUTER_API_KEY")
            )
            clients["openrouter"] = track_openai(openrouter_client, project_name=PROJECT_NAME)
            print("✅ OpenRouter client initialized")
        except Exception as e:
            print(f"⚠️  OpenRouter client failed to initialize: {e}")
    else:
        print("⚠️  OPENROUTER_API_KEY not set")

    # Google Gemini client via ADK (preferred) or Google GenAI (fallback)
    gemini_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if gemini_key:
        import sys
        print("🔍 DEBUG: Gemini API key detected; looking for Google ADK...")
        try:
            # Detect ADK presence (no Client class; used for agents, not image generation)
            try:
                import google.adk  # type: ignore
                clients["google_adk_available"] = True
                print("✅ Google ADK detected (for agents)")
            except Exception as adk_detect_e:
                print(f"🔍 DEBUG: Google ADK not importable: {adk_detect_e}")
            # Initialize Google GenAI official client for image generation
            try:
                from google import genai  # type: ignore
                genai_client = genai.Client(api_key=gemini_key)
                clients["google"] = genai_client
                clients["google_provider"] = "genai"
                clients["google_api_key"] = gemini_key
                print("✅ Google GenAI client initialized (Gemini API)")
            except Exception as ge:
                print(f"⚠️  Google GenAI init failed: {ge}")
        except Exception as e:
            print(f"⚠️  Google Gemini client failed to initialize: {e}")
            import traceback
            traceback.print_exc()
    else:
        print("⚠️  GEMINI_API_KEY (or GOOGLE_API_KEY) not set")

    # OpenAI Agents setup
    if OPENAI_AGENTS_AVAILABLE and os.environ.get("OPENAI_API_KEY"):
        try:
            # Set up Opik tracing for OpenAI Agents
            set_trace_processors(processors=[OpikTracingProcessor(project_name=PROJECT_NAME)])
            clients["openai_agents"] = True  # Mark as available
            print("✅ OpenAI Agents with Opik tracing initialized")
        except Exception as e:
            print(f"⚠️  OpenAI Agents setup failed: {e}")
    else:
        print("⚠️  OpenAI Agents not available")

    return clients


# Initialize all clients
clients = initialize_clients()


def fetch_and_dump_recent_traces(opik_client, label: str):
    """Fetch and dump the most recent traces from Opik"""
    print("\n" + "=" * 80)
    print(f"DEBUG: {label}")
    print("=" * 80)

    try:
        # Give Opik time to flush the traces
        time.sleep(3)

        # Search for recent traces
        traces = opik_client.search_traces(project_name=PROJECT_NAME, max_results=5)

        if traces:
            print(f"\nFound {len(traces)} recent traces. Showing the most recent one:")
            latest_trace = traces[0]

            print("\n--- LATEST TRACE ---")
            print(f"ID: {latest_trace.id}")
            print(f"Name: {latest_trace.name}")

            print("\n--- INPUT STRUCTURE ---")
            print(json.dumps(latest_trace.input, indent=2, default=str))

            print("\n--- OUTPUT STRUCTURE ---")
            print(json.dumps(latest_trace.output, indent=2, default=str))

            print("\n--- METADATA ---")
            if latest_trace.metadata:
                print(json.dumps(latest_trace.metadata, indent=2, default=str))

            # Check for spans
            print("\n--- SPANS ---")
            if hasattr(latest_trace, 'spans') or hasattr(latest_trace, 'get_spans'):
                try:
                    spans = latest_trace.spans if hasattr(latest_trace, 'spans') else []
                    print(f"Number of spans: {len(spans)}")
                    for i, span in enumerate(spans):
                        print(f"\nSpan {i + 1}:")
                        print(f"  Name: {span.name if hasattr(span, 'name') else 'N/A'}")
                        if hasattr(span, 'input'):
                            print(f"  Input: {json.dumps(span.input, indent=4, default=str)[:500]}...")
                        if hasattr(span, 'output'):
                            print(f"  Output: {json.dumps(span.output, indent=4, default=str)[:500]}...")
                except Exception as e:
                    print(f"Error accessing spans: {e}")
            else:
                print("No spans attribute found")

        else:
            print("\nNo traces found!")

    except Exception as e:
        print(f"Error fetching traces: {e}")
        import traceback
        traceback.print_exc()

    print("=" * 80 + "\n")


@opik.track(project_name=PROJECT_NAME, name="openai_dalle3")
def test_openai_image_generation(prompt: str):
    """Test 1: Generate image with DALL-E using OpenAI integration"""
    print("\n" + "=" * 60)
    print("TEST 1: Simple OpenAI DALL-E 3 (images.generate)")
    print("=" * 60)

    if "openai" not in clients:
        print("❌ OpenAI client not available")
        return None, None

    print(f"Generating image with prompt: {prompt}")

    try:
        # Generate image - automatically tracked by Opik
        response = clients["openai"].images.generate(
            model="dall-e-3",
            prompt=prompt,
            size="1024x1024",
            quality="standard",
            n=1,
        )

        image_url = response.data[0].url
        revised_prompt = response.data[0].revised_prompt

        print(f"✓ Image generated: {image_url}")
        print(f"✓ Revised prompt: {revised_prompt[:100]}...")
        print(f"✓ Logged to Opik project: {PROJECT_NAME}")

        return image_url, revised_prompt
    except Exception as e:
        print(f"❌ OpenAI image generation failed: {e}")
        import traceback
        traceback.print_exc()
        return None, None


@opik.track(project_name=PROJECT_NAME, name="openai_gpt_image1")
def test_openai_gpt_image_generation(prompt: str):
    """Test 2: Generate image using OpenAI gpt-image-1 (Images API)"""
    print("\n" + "=" * 60)
    print("TEST 2: OpenAI Image Generation (gpt-image-1 via Images API)")
    print("=" * 60)

    if "openai" not in clients:
        print("❌ OpenAI client not available")
        return None, None

    print(f"Generating image with prompt: {prompt}")

    try:
        # Use the Images API with gpt-image-1 (quality: low|medium|high|auto)
        img = clients["openai"].images.generate(
            model="gpt-image-1",
            prompt=prompt,
            size="1024x1024",
            quality="low",
            n=1,
        )
        # Try URL first
        url = None
        try:
            url = img.data[0].url
        except Exception:
            url = None
        if not url:
            # Some SDKs return base64 instead
            b64 = getattr(img.data[0], "b64_json", None)
            if b64:
                url = f"data:image/png;base64,{b64}"
        if url:
            print(f"✓ Image generated: {url[:80]}...")
            print(f"✓ Logged to Opik project: {PROJECT_NAME}")
            return url, prompt
        print("⚠️  No URL or base64 returned by Images API for gpt-image-1. Skipping.")
        return None, None
    except Exception as e:
        print(f"❌ OpenAI gpt-image-1 images.generate failed: {e}")
        import traceback
        traceback.print_exc()
        return None, None


@opik.track(project_name=PROJECT_NAME, name="openrouter_gemini_image")
def test_openrouter_gemini_image_generation(prompt: str):
    """Test X: Generate image using Gemini via OpenRouter"""
    print("\n" + "=" * 60)
    print("TEST 2: Gemini 2.5 Flash Image Generation (via OpenRouter)")
    print("=" * 60)

    if "openrouter" not in clients:
        print("❌ OpenRouter client not available")
        return None, None

    print(f"Generating image with prompt: {prompt}")

    try:
        # Use Gemini 2.5 Flash Image model through OpenRouter
        # Per docs: send to /chat/completions with modalities ["image","text"]
        # https://openrouter.ai/docs/features/multimodal/image-generation
        response = clients["openrouter"].chat.completions.create(
            model="google/gemini-2.5-flash-image-preview",
            messages=[
                {
                    "role": "user",
                    "content": prompt
                }
            ],
            modalities=["image", "text"],
            max_tokens=1000
        )

        # Extract image per docs: assistant message includes images list with image_url.url (base64 data URL)
        image_url = None
        try:
            message = response.choices[0].message
        except Exception:
            message = None
        image_url = _extract_image_url(message) or _extract_image_url(response)
        if not image_url:
            # Fallback: regex scan for data URL in stringified response
            try:
                import re
                blob = json.dumps(response, default=str)
                m = re.search(r"data:image\/(?:png|jpeg|jpg);base64,[A-Za-z0-9+\/=]+", blob)
                if m:
                    image_url = m.group(0)
            except Exception:
                pass
        if not image_url:
            raise Exception(
                "No image found in OpenRouter response; ensure model supports image output and modalities were set")

        print(f"✓ Image generated: {image_url[:50]}...")
        print(f"✓ Logged to Opik project: {PROJECT_NAME}")

        return image_url, prompt
    except Exception as e:
        print(f"❌ Gemini image generation via OpenRouter failed: {e}")
        print(f"   This might mean:")
        print(f"   - The model 'google/gemini-2.5-flash-image-preview' isn't available")
        print(f"   - OpenRouter API structure has changed")
        print(f"   - Check OpenRouter documentation for current image generation API")
        import traceback
        traceback.print_exc()
        return None, None


@opik.track(project_name=PROJECT_NAME, name="google_gemini_image")
def test_google_gemini_image_generation(prompt: str):
    """Test X: Generate image using Google Gemini via Google ADK / Generative AI"""
    print("\n" + "=" * 60)
    print("TEST 3: Google Gemini (via Google ADK)")
    print("=" * 60)

    if "google" not in clients:
        print("❌ Google Gemini client not available (ADK or Generative AI)")
        return None, None

    print(f"Generating image with prompt: {prompt}")

    try:
        provider = clients.get("google_provider")
        image_url = None
        revised_prompt = prompt
        if provider == "adk":
            # Prefer generating images via Google GenAI even if ADK is present
            try:
                from google import genai  # type: ignore
                genai_key = clients.get("google_api_key") or os.environ.get("GOOGLE_API_KEY") or os.environ.get(
                    "GEMINI_API_KEY")
                genai_client = genai.Client(api_key=genai_key) if genai_key else genai.Client()
                try:
                    from google.genai import types as genai_types  # type: ignore
                except Exception:
                    genai_types = None
                result = genai_client.models.generate_images(
                    model='imagen-3.0-generate-002',
                    prompt=prompt,
                    config=(genai_types.GenerateImagesConfig(
                        number_of_images=1,
                        output_mime_type='image/jpeg',
                    ) if genai_types else dict(number_of_images=1, output_mime_type='image/jpeg'))
                )
                gi = result.generated_images[0]
                img_bytes = gi.image.image_bytes
                if isinstance(img_bytes, (bytes, bytearray)):
                    b64 = base64.b64encode(img_bytes).decode('utf-8')
                    image_url = f"data:image/jpeg;base64,{b64}"
                elif hasattr(gi.image, 'uri') and gi.image.uri:
                    image_url = gi.image.uri
            except Exception as adk_genai_e:
                print(f"⚠️  ADK path using Google GenAI failed: {adk_genai_e}")
                # Last resort: call ADK client if it exposes generate_image
                try:
                    if hasattr(clients["google"], "generate_image"):
                        response = clients["google"].generate_image(
                            prompt=prompt,
                            model="gemini-2.0-flash-exp",
                            size="1024x1024"
                        )
                        image_url = (
                                response.get("image_url") or response.get("url") or response.get("data", {}).get("url")
                        )
                        revised_prompt = response.get("revised_prompt", prompt)
                except Exception as adk_direct_e:
                    print(f"⚠️  ADK direct image generation failed: {adk_direct_e}")
        elif provider == "genai":
            # Google GenAI official client: prefer Gemini native image generation (preview)
            # https://ai.google.dev/gemini-api/docs/image-generation
            client_genai = clients["google"]
            try:
                response = client_genai.models.generate_content(
                    model="gemini-2.5-flash-image-preview",
                    contents=[prompt],
                )
                # Extract inline image bytes
                try:
                    parts = response.candidates[0].content.parts
                except Exception:
                    parts = []
                for part in parts:
                    inline_data = getattr(part, "inline_data", None)
                    if inline_data and getattr(inline_data, "data", None):
                        b64 = inline_data.data if isinstance(inline_data.data, str) else base64.b64encode(
                            inline_data.data).decode("utf-8")
                        image_url = f"data:image/png;base64,{b64}"
                        break
                if not image_url:
                    # Fallback to Imagen generate_images
                    try:
                        from google.genai import types as genai_types  # type: ignore
                    except Exception:
                        genai_types = None
                    result = client_genai.models.generate_images(
                        model='imagen-3.0-generate-002',
                        prompt=prompt,
                        config=(genai_types.GenerateImagesConfig(
                            number_of_images=1,
                            output_mime_type='image/jpeg',
                        ) if genai_types else dict(number_of_images=1, output_mime_type='image/jpeg'))
                    )
                    gi = result.generated_images[0]
                    img_bytes = gi.image.image_bytes
                    if isinstance(img_bytes, (bytes, bytearray)):
                        b64 = base64.b64encode(img_bytes).decode('utf-8')
                        image_url = f"data:image/jpeg;base64,{b64}"
                    elif hasattr(gi.image, 'uri') and gi.image.uri:
                        image_url = gi.image.uri
            except Exception as ge:
                print(f"⚠️  Google GenAI generate_content failed: {ge}")
                image_url = None
        else:
            # Legacy google.generativeai path (kept as last-resort)
            result = clients["google"].generate_content([prompt])
            try:
                parts = getattr(result, "candidates", [])[0].content.parts  # type: ignore
            except Exception:
                parts = []
            for p in parts:
                uri = getattr(p, "file_data", None) or getattr(p, "inline_data", None)
                if uri and getattr(uri, "mime_type", "").startswith("image/"):
                    image_url = getattr(uri, "file_uri", None) or getattr(uri, "data", None)
                    break

        if not image_url:
            print("❌ No image URL found in Gemini response")
            return None, None

        print(f"✓ Image generated: {image_url}")
        print(f"✓ Logged to Opik project: {PROJECT_NAME}")

        return image_url, revised_prompt
    except Exception as e:
        print(f"❌ Google Gemini image generation failed: {e}")
        print(f"   This might mean the model isn't available or the API has changed")
        import traceback
        traceback.print_exc()
        return None, None


# OpenAI Agents Function Tools for Multimodal Operations
if OPENAI_AGENTS_AVAILABLE:
    @function_tool
    def generate_image_with_dalle(prompt: str, size: str = "1024x1024", quality: str = "standard") -> dict:
        """Generate an image using DALL-E 3 through OpenAI API"""
        try:
            if "openai" not in clients:
                return {"error": "OpenAI client not available"}

            response = clients["openai"].images.generate(
                model="dall-e-3",
                prompt=prompt,
                size=size,
                quality=quality,
                n=1,
            )

            return {
                "success": True,
                "image_url": response.data[0].url,
                "revised_prompt": response.data[0].revised_prompt
            }
        except Exception as e:
            return {"error": f"Image generation failed: {str(e)}"}


    @function_tool
    def analyze_image_with_vision(image_url: str, analysis_prompt: str = "Describe this image in detail") -> dict:
        """Analyze an image using GPT-4o Vision"""
        try:
            if "openai" not in clients:
                return {"error": "OpenAI client not available"}

            response = clients["openai"].chat.completions.create(
                model="gpt-4o",
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": analysis_prompt},
                            {"type": "image_url", "image_url": {"url": image_url}},
                        ],
                    }
                ],
                max_tokens=500,
            )

            return {
                "success": True,
                "analysis": response.choices[0].message.content
            }
        except Exception as e:
            return {"error": f"Vision analysis failed: {str(e)}"}


    @function_tool
    def analyze_image_with_claude(image_url: str, analysis_prompt: str = "Describe this image in detail") -> dict:
        """Analyze an image using Claude Vision"""
        try:
            if "anthropic" not in clients:
                return {"error": "Anthropic client not available"}

            response = clients["anthropic"].messages.create(
                model="claude-3-5-sonnet-20241022",
                max_tokens=500,
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": analysis_prompt},
                            {
                                "type": "image",
                                "source": {
                                    "type": "url",
                                    "url": image_url,
                                    "media_type": "image/jpeg"
                                }
                            }
                        ]
                    }
                ]
            )

            return {
                "success": True,
                "analysis": response.content[0].text
            }
        except Exception as e:
            return {"error": f"Claude vision analysis failed: {str(e)}"}


def test_openai_agents_multimodal():
    """Test X: OpenAI Agents with multimodal function tools"""
    print("\n" + "=" * 60)
    print("TEST 7: OpenAI Agents Multimodal Operations")
    print("=" * 60)

    if "openai_agents" not in clients:
        print("❌ OpenAI Agents not available")
        return None

    try:
        # Create a multimodal agent with image generation and analysis tools
        multimodal_agent = Agent(
            name="MultimodalAssistant",
            instructions="""You are a multimodal AI assistant with access to image generation and analysis tools. 
            You can:
            1. Generate images using DALL-E 3
            2. Analyze images using GPT-4o Vision
            3. Analyze images using Claude Vision
            
            When asked to create or analyze images, use the appropriate tools and provide detailed responses.
            Always explain what you're doing and provide the results clearly.""",
            model="gpt-4o-mini",
            tools=[generate_image_with_dalle, analyze_image_with_vision, analyze_image_with_claude]
        )

        # Test 1: Generate and analyze an image
        print("🤖 Testing image generation and analysis workflow...")

        result = Runner.run_sync(
            multimodal_agent,
            "Generate an image of a futuristic AI laboratory and then analyze it in detail. Use both GPT-4o and Claude for analysis to compare their perspectives."
        )

        print(f"✅ Agent response: {result.final_output[:200]}...")
        print(f"✅ Logged to Opik project: {PROJECT_NAME}")

        return result.final_output

    except Exception as e:
        print(f"❌ OpenAI Agents multimodal test failed: {e}")
        return None


def test_openai_agents_conversation():
    """Test X: OpenAI Agents multi-turn conversation with image context"""
    print("\n" + "=" * 60)
    print("TEST 8: OpenAI Agents Multi-turn Conversation")
    print("=" * 60)

    if "openai_agents" not in clients:
        print("❌ OpenAI Agents not available")
        return None

    try:
        import uuid
        from agents import trace

        # Create a conversational agent
        conversation_agent = Agent(
            name="ConversationalAssistant",
            instructions="You are a helpful assistant that can generate and analyze images. Be conversational and engaging.",
            model="gpt-4o-mini",
            tools=[generate_image_with_dalle, analyze_image_with_vision]
        )

        # Create a conversation thread
        thread_id = str(uuid.uuid4())
        print(f"🧵 Starting conversation thread: {thread_id}")

        with trace(workflow_name="MultimodalConversation", group_id=thread_id):
            # First turn: Generate an image
            print("📝 Turn 1: Generating an image...")
            result1 = Runner.run_sync(
                conversation_agent,
                "Create an image of a beautiful sunset over mountains"
            )
            print(f"🤖 Response 1: {result1.final_output[:150]}...")

            # Extract image URL from the response (this would need parsing in a real scenario)
            # For now, we'll simulate a follow-up question
            print("📝 Turn 2: Asking about the image...")
            result2 = Runner.run_sync(
                conversation_agent,
                "Can you analyze the image you just created and tell me about the colors and mood?"
            )
            print(f"🤖 Response 2: {result2.final_output[:150]}...")

        print(f"✅ Multi-turn conversation completed")
        print(f"✅ Logged to Opik project: {PROJECT_NAME}")

        return {
            "thread_id": thread_id,
            "turn1": result1.final_output,
            "turn2": result2.final_output
        }

    except Exception as e:
        print(f"❌ OpenAI Agents conversation test failed: {e}")
        return None


def test_openai_agents_gpt5_image_generation(prompt: str):
    """Test X: OpenAI Agent SDK using gpt-5 to directly generate an image"""
    print("\n" + "=" * 60)
    print("TEST X: OpenAI Agent SDK (gpt-5 direct image generation)")
    print("=" * 60)

    if "openai_agents" not in clients:
        print("❌ OpenAI Agents not available")
        return None, None

    try:
        agent = Agent(
            name="GPT5ImageAgent",
            instructions=(
                "You can generate images directly. When asked to create an image, "
                "produce the image and include a link or data reference in your response."
            ),
            model="gpt-5",
            tools=[]
        )

        result = Runner.run_sync(agent, f"Generate an image: {prompt}")

        image_url = None
        # Best-effort extraction from potential result structures
        for attr in ("artifacts", "attachments"):
            if hasattr(result, attr):
                items = getattr(result, attr) or []
                try:
                    for it in items:
                        if isinstance(it, dict):
                            image_url = it.get("image_url") or it.get("url")
                            if image_url:
                                break
                        else:
                            iu = getattr(it, "image_url", None) or getattr(it, "url", None)
                            if iu:
                                image_url = iu
                                break
                except Exception:
                    pass

        # Fallback: try to find a URL in final_output text
        if not image_url and hasattr(result, "final_output") and isinstance(result.final_output, str):
            import re
            m = re.search(r"https?://\S+", result.final_output)
            if m:
                image_url = m.group(0)

        if image_url:
            print(f"✓ Agent generated image: {image_url[:80]}...")
        else:
            print("⚠️  Agent response did not contain a direct image URL; see Opik trace for details")
            if hasattr(result, "final_output"):
                print(f"📝 Agent output (truncated): {str(result.final_output)[:200]}...")

        print(f"✓ Logged to Opik project: {PROJECT_NAME}")
        return image_url, prompt
    except Exception as e:
        print(f"❌ OpenAI Agent gpt-5 image generation failed: {e}")
        return None, None


def print_online_eval_instructions():
    """Print instructions for setting up online evaluation"""
    print("\n" + "=" * 60)
    print("ONLINE EVALUATION SETUP INSTRUCTIONS")
    print("=" * 60)

    print(f"\n1. Go to Opik UI → Projects → '{PROJECT_NAME}'")
    print("\n2. Click 'Online evaluation' → 'Create rule'")
    print("\n3. Configure the rule:")
    print("   - Name: Image Quality Judge")
    print("   - Scope: Trace (NOT Thread - images not supported at thread level)")
    print("   - Type: LLM-as-a-Judge")
    print("   - Provider: OpenAI (gpt-4o or gpt-5)")

    print("\n4. Add this prompt (for rating image quality):")
    print("-" * 60)
    print("""
You are an image quality evaluator. Rate the quality of this generated image on a scale of 1-10, considering composition, clarity, coherence, and adherence to the intended subject.

{{image}}
""")
    print("-" * 60)

    print("\n5. Variable mapping:")
    print("   - Variable name: image")
    print("   - Maps to: input.messages[0].content[1].image_url.url")
    print("   - (For vision analysis traces, the image is in the input)")

    print("\n6. Schema (Output score):")
    print("   - Name: Quality")
    print("   - Description: Whether the output is of sufficient quality")
    print("   - Type: INTEGER")

    print("\n7. Save the rule and run the tests again!")
    print("\n⚠️  IMPORTANT: Images are only supported for Trace-level evaluation.")
    print("   Thread-level evaluation does not support images.")


def run_comprehensive_multimodal_test(prompt: str = DEFAULT_PROMPT):
    """Run comprehensive image generation tests across all available providers"""
    print("\n🎨 OPIK IMAGE GENERATION TESTING ACROSS ALL PROVIDERS")
    print("=" * 80)
    print(f"\n📝 Using prompt: {prompt}")
    print("=" * 80)

    # Check for API keys
    available_keys = []
    if os.environ.get("OPENAI_API_KEY"):
        available_keys.append("OpenAI DALL-E 3")
    if os.environ.get("OPENROUTER_API_KEY"):
        available_keys.append("OpenRouter (Gemini 2.5 Flash Image)")
    if os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY"):
        available_keys.append("Google Gemini (via ADK/GenerativeAI)")

    if not available_keys:
        print("❌ ERROR: No API keys found!")
        print("   Set at least one of:")
        print("   export OPENAI_API_KEY='sk-...'")
        print("   export OPENROUTER_API_KEY='sk-or-...'")
        print("   export GEMINI_API_KEY='...'  # or GOOGLE_API_KEY")
        exit(1)

    print(f"✅ Available providers: {', '.join(available_keys)}")

    # Skip model listing for faster boot
    # (Model discovery can be slow and is unnecessary when models are fixed)

    # Initialize Opik client for fetching traces
    # Initialize Opik client for fetching traces
    opik_client = opik.Opik()
    # Test results storage
    results = {
        "image_generation": {}
    }

    # IMAGE GENERATION TESTS
    print("\n" + "=" * 80)
    print("IMAGE GENERATION TESTS")
    print("=" * 80)

    # Test 1: OpenAI DALL-E 3
    image_url, revised_prompt = test_openai_image_generation(prompt)
    if image_url:
        results["image_generation"]["openai_dalle3"] = {
            "url": image_url,
            "revised_prompt": revised_prompt,
            "provider": "OpenAI DALL-E 3"
        }
        fetch_and_dump_recent_traces(opik_client, "AFTER OPENAI DALLE-E 3 IMAGE GENERATION")

    # Test 2: OpenAI gpt-image-1 (Responses) image generation
    image_url, revised_prompt = test_openai_gpt_image_generation(prompt)
    if image_url:
        results["image_generation"]["openai_gpt_image_1_responses"] = {
            "url": image_url,
            "revised_prompt": revised_prompt or prompt,
            "provider": "OpenAI gpt-image-1 (Responses)"
        }
        fetch_and_dump_recent_traces(opik_client, "AFTER OPENAI GPT-IMAGE-1 RESPONSES IMAGE GENERATION")

    # Test 3: Gemini 2.5 Flash Image via OpenRouter
    image_url, revised_prompt = test_openrouter_gemini_image_generation(prompt)
    if image_url:
        results["image_generation"]["gemini_openrouter"] = {
            "url": image_url,
            "revised_prompt": revised_prompt,
            "provider": "Gemini 2.5 Flash Image (via OpenRouter)"
        }
        fetch_and_dump_recent_traces(opik_client, "AFTER GEMINI OPENROUTER IMAGE GENERATION")

    # Test 4: Google Gemini via Google ADK
    image_url, revised_prompt = test_google_gemini_image_generation(prompt)
    if image_url:
        results["image_generation"]["google_gemini"] = {
            "url": image_url,
            "revised_prompt": revised_prompt,
            "provider": "Google Gemini (via Google ADK)"
        }
        fetch_and_dump_recent_traces(opik_client, "AFTER GOOGLE GEMINI IMAGE GENERATION")

    # Test 5: OpenAI Agent SDK with gpt-5 direct image generation
    image_url, revised_prompt = test_openai_agents_gpt5_image_generation(prompt)
    if image_url:
        results["image_generation"]["openai_agent_gpt5"] = {
            "url": image_url,
            "revised_prompt": revised_prompt,
            "provider": "OpenAI Agent SDK (gpt-5)"
        }
        fetch_and_dump_recent_traces(opik_client, "AFTER OPENAI AGENT GPT-5 IMAGE GENERATION")

    # Show comprehensive results
    print("\n" + "=" * 80)
    print("✅ IMAGE GENERATION TEST RESULTS")
    print("=" * 80)

    if results["image_generation"]:
        print("\n📸 GENERATED IMAGES:")
        for provider_key, data in results["image_generation"].items():
            print(f"\n  {data['provider']}: ✅ Success")
            print(f"    URL: {data['url']}")
            print(f"    Revised Prompt: {data['revised_prompt'][:100]}...")
    else:
        print("\n⚠️  No images were successfully generated")

        # Print online eval instructions
        print_online_eval_instructions()

    print("\n✅ All tests logged to Opik successfully!")
    print(f"Check your Opik UI at http://localhost:5173 (or your Opik URL)")
    print(f"Project: {PROJECT_NAME}\n")

    print("\n" + "=" * 80)
    print("IMPORTANT: Review the DEBUG sections above to find the exact field paths")
    print("that contain the image URLs in the Opik trace structure.")
    print("Use those paths when mapping variables in the online evaluator.")
    print("=" * 80 + "\n")

    return results


if __name__ == "__main__":
    try:
        # Get custom prompt from command line argument if provided
        import sys

        custom_prompt = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PROMPT

        run_comprehensive_multimodal_test(prompt=custom_prompt)
    except Exception as e:
        print(f"\n❌ ERROR: {e}")
        import traceback

        traceback.print_exc()

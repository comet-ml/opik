"""
Sample: traces carrying an image attachment for the online LLM-as-judge eval (OPIK-6555).

Creates two artifacts so you can verify both code paths:

  1. A standalone single trace (NO thread_id) with an image attachment — exercises the
     trace-level LLM-as-judge attachment routing (the {{trace}} variable + agentic-tools
     switch when a trace has attachments).
  2. A 3-turn conversation thread that mimics a vision-Q&A session, with the image on
     turn 1 — exercises the thread-level path.

Pass --single or --thread to create just one of them (default: both).

The image is attached so the online LLM-as-judge eval can fetch it via get_attachment
and score it.

Usage:
    pip install opik pillow requests
    OPIK_API_KEY=... OPIK_WORKSPACE=... python thread_with_image_attachment.py

    # Just the single trace (to verify the latest trace-level change):
    python thread_with_image_attachment.py --single

    # Or point at a local instance:
    OPIK_URL_OVERRIDE=http://localhost:5173/api python thread_with_image_attachment.py
"""

import datetime
import os
import uuid

import opik
from opik import Attachment, id_helpers


def _now() -> datetime.datetime:
    """UTC now — used to stamp end_time so traces count as 'complete'.

    Online scoring (OnlineScoringSampler) skips traces with no end_time, treating
    them as partial/in-flight. A one-shot client.trace(...) call does NOT set end_time
    on its own, so we set it explicitly here or the eval rule never fires.
    """
    return datetime.datetime.now(tz=datetime.timezone.utc)


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

PROJECT_NAME = os.getenv("OPIK_PROJECT_NAME", "image-attachment-demo")

# Use a tiny sample image embedded as bytes so the script is self-contained.
# If you have a real image on disk, replace this with its path (string).
SAMPLE_IMAGE_PATH: str | None = os.getenv("IMAGE_PATH", None)


def _make_sample_png_bytes() -> bytes:
    """Build a minimal 1×1 red PNG in pure Python (no Pillow required)."""
    import struct
    import zlib

    def chunk(name: bytes, data: bytes) -> bytes:
        c = name + data
        return (
            struct.pack(">I", len(data))
            + c
            + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
        )

    signature = b"\x89PNG\r\n\x1a\n"
    ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
    raw_row = b"\x00\xff\x00\x00"  # filter byte 0, R=255 G=0 B=0
    idat = chunk(b"IDAT", zlib.compress(raw_row))
    iend = chunk(b"IEND", b"")
    return signature + ihdr + idat + iend


# ---------------------------------------------------------------------------
# Build the image attachment
# ---------------------------------------------------------------------------


def build_attachment() -> tuple[Attachment, str]:
    """Return (Attachment, file_name)."""
    if SAMPLE_IMAGE_PATH and os.path.isfile(SAMPLE_IMAGE_PATH):
        file_name = os.path.basename(SAMPLE_IMAGE_PATH)
        return (
            Attachment(
                data=SAMPLE_IMAGE_PATH,
                file_name=file_name,
                content_type="image/png",
            ),
            file_name,
        )

    # Fall back to the embedded 1×1 PNG
    png_bytes = _make_sample_png_bytes()
    file_name = "sample-image.png"
    return (
        Attachment(
            data=png_bytes,
            file_name=file_name,
            content_type="image/png",
        ),
        file_name,
    )


# ---------------------------------------------------------------------------
# Create a single standalone trace (no thread)
# ---------------------------------------------------------------------------


def create_single_trace(client: opik.Opik) -> str:
    """Create one standalone trace (no thread_id) carrying the image attachment.

    This is the artifact for verifying the trace-level LLM-as-judge attachment
    routing: when the trace has attachments and the toggle is on, scoring switches
    to the agentic-tools path so the judge can load the media via get_attachment,
    and the {{trace}} variable lists the attachment.
    """
    trace_id = id_helpers.generate_id()
    attachment, image_file_name = build_attachment()

    client.trace(
        id=trace_id,
        name="single-trace-vision-question",
        project_name=PROJECT_NAME,
        end_time=_now(),
        input={
            "role": "user",
            "content": (
                f"I've attached an image ({image_file_name}). "
                "Can you describe what you see and identify any anomalies?"
            ),
        },
        output={
            "role": "assistant",
            "content": (
                "I can see an image. It appears to contain a solid red pixel. "
                "I notice it is an extremely small (1×1) image."
            ),
        },
        tags=["image", "vision", "single-trace"],
        attachments=[attachment],
    )

    return trace_id


# ---------------------------------------------------------------------------
# Create the thread
# ---------------------------------------------------------------------------


def create_thread(client: opik.Opik) -> str:
    thread_id = str(uuid.uuid4())
    attachment, image_file_name = build_attachment()

    # ------------------------------------------------------------------
    # Turn 1 — user sends an image and asks a question
    # ------------------------------------------------------------------
    turn1_id = id_helpers.generate_id()
    client.trace(
        id=turn1_id,
        name="turn-1-user-question",
        thread_id=thread_id,
        project_name=PROJECT_NAME,
        end_time=_now(),
        input={
            "role": "user",
            "content": (
                f"I've attached an image ({image_file_name}). "
                "Can you describe what you see and identify any anomalies?"
            ),
        },
        output={
            "role": "assistant",
            "content": (
                "I can see an image. It appears to contain a solid red pixel. "
                "I notice it is an extremely small (1×1) image — could you confirm "
                "whether this is intentional or a rendering issue?"
            ),
        },
        tags=["image", "vision", "turn-1"],
        attachments=[attachment],
    )

    # ------------------------------------------------------------------
    # Turn 2 — assistant asks a follow-up (no attachment needed here)
    # ------------------------------------------------------------------
    turn2_id = id_helpers.generate_id()
    client.trace(
        id=turn2_id,
        name="turn-2-clarification",
        thread_id=thread_id,
        project_name=PROJECT_NAME,
        end_time=_now(),
        input={
            "role": "user",
            "content": "It is intentional — it's a test image.",
        },
        output={
            "role": "assistant",
            "content": (
                "Understood. The image is a 1×1 PNG with a single red (#FF0000) pixel. "
                "No anomalies detected. Is there anything specific you'd like me to "
                "analyse about the colour or format?"
            ),
        },
        tags=["vision", "turn-2"],
    )

    # ------------------------------------------------------------------
    # Turn 3 — user wraps up; assistant gives a final summary
    # ------------------------------------------------------------------
    turn3_id = id_helpers.generate_id()
    client.trace(
        id=turn3_id,
        name="turn-3-summary",
        thread_id=thread_id,
        project_name=PROJECT_NAME,
        end_time=_now(),
        input={
            "role": "user",
            "content": "No, that covers it. Thanks!",
        },
        output={
            "role": "assistant",
            "content": (
                "You're welcome! To summarise: the image is a minimal 1×1 PNG containing "
                "a pure red pixel with no anomalies. Let me know if you have more images "
                "to analyse."
            ),
        },
        tags=["vision", "turn-3"],
    )

    return thread_id


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--single", action="store_true", help="Create only the standalone single trace."
    )
    group.add_argument(
        "--thread", action="store_true", help="Create only the multi-turn thread."
    )
    args = parser.parse_args()

    do_single = args.single or not args.thread
    do_thread = args.thread or not args.single

    client = opik.Opik(project_name=PROJECT_NAME)

    single_trace_id = None
    thread_id = None

    if do_single:
        print(f"Creating single trace in project '{PROJECT_NAME}' ...")
        single_trace_id = create_single_trace(client)

    if do_thread:
        print(f"Creating thread in project '{PROJECT_NAME}' ...")
        thread_id = create_thread(client)

    client.flush()

    print(f"\nCreated successfully in project '{PROJECT_NAME}'.")
    if single_trace_id is not None:
        print(f"  single trace_id : {single_trace_id}")
    if thread_id is not None:
        print(f"  thread_id       : {thread_id}")
    print()
    print(
        "To verify the trace-level change, create a TRACE-level LLM-as-judge rule that"
    )
    print("references {{trace}} (map a variable to the bare string 'trace'), using a")
    print("vision-capable, tool-calling model, with the agentic-tools toggle enabled.")
    print(
        "Scoring should switch to the agentic-tools path, read(type=trace) should list"
    )
    print("the attachment, and get_attachment should load it before a score is stored.")


if __name__ == "__main__":
    main()

---
sidebar_label: Log Multimodal Traces
description: Describes how to log and view images in traces to the Opik platform
toc_min_heading_level: 2
toc_max_heading_level: 4
---

# Log Multimodal Traces

Opik supports multimodal traces allowing you to track not just the text input and output of your LLM, but also images.

![Traces with OpenAI](/img/tracing/image_trace.png)

## Log a trace with an image using OpenAI SDK

Images logged to a trace in both base64 encoded images and as URLs are displayed in the trace sidebar.

We recommend that you use the [`track_openai`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/openai/track_openai.html) wrapper to ensure the OpenAI API call is traced correctly:

```python
from opik.integrations.openai import track_openai
from openai import OpenAI

# Create the OpenAI client and enable Opik tracing
client = track_openai(OpenAI())

response = client.chat.completions.create(
  model="gpt-4o-mini",
  messages=[
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "What’s in this image?"},
        {
          "type": "image_url",
          "image_url": {
            "url": "https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg",
          },
        },
      ],
    }
  ],
  max_tokens=300,
)

print(response.choices[0])
```

## Manually logging images

If you are not using the OpenAI SDK, you can still log images to the platform. The UI will automatically detect images based on regex rules as long as the images are logged as base64 encoded images or urls ending with `.png`, `.jpg`, `.jpeg`, `.gif`, `.bmp`, `.webp`:

```json
{
  "image": "<url or base64 encoded image>"
}
```

:::tip
Let's us know on [Github](https://github.com/comet-ml/opik/issues/new/choose) if you would like to us to support additional image formats or models.
:::

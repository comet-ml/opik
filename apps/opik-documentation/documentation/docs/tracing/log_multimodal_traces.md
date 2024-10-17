---
sidebar_position: 2
sidebar_label: Log Multimodal Traces
toc_min_heading_level: 2
toc_max_heading_level: 4
---

# Log Multimodal Traces

Opik supports multimodal traces allowing you to track not just the text input and output of your LLM, but also images.

![Traces with OpenAI](/img/tracing/image_trace.png)

## Log a trace with an image using OpenAI SDK

As long as your trace input or output follows the OpenAI format, images will automatically be detected and rendered in the Opik UI.

We recommend that you use the [`track_openai`](/python-sdk-reference/integrations/openai/track_openai.html) wrapper to ensure the OpenAI API call is traced correctly:

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

If you are not using the OpenAI SDK, you can still log images to the platform. The UI will automatically detect the image and display it if the input field has a `message` attribute that follows the OpenAI format:

```json
{
    "messages": [
        ...,
        {
            "type": "image_url",
            "image_url": {
                "url": "<url or base64 encoded image>"
            }
        }
    ],
    ...
}
```

:::tip
Let's us know on [Github](https://github.com/comet-ml/opik/issues/new/choose) if you would like to us to support additional image formats or models.
:::

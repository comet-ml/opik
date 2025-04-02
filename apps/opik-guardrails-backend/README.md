# Opik Guardrails Backend

This is the backend service for Opik Guardrails.

## Running with Docker

### Prerequisites

To run the Opik Guardrails Backend with Docker, you need to have Docker installed on your system.
In addition, to use GPU acceleration, you need to have the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html) installed.

### Running the Backend

#### Option 1: Build locally (only needed once)

```bash
cd apps/opik-guardrails-backend
docker build -t opik-guardrails-backend:latest .
```

#### Option 2: Run the image

With GPU support
```bash
docker run -p 5000:5000 --gpus all opik-guardrails-backend:latest
```
Without GPU (CPU only)
```bash
docker run -p 5000:5000 opik-guardrails-backend:latest
```

The server will be available at http://localhost:5000

### GPU Support

The Opik Guardrails Backend is configured to automatically detect and use NVIDIA GPUs if available. The container uses NVIDIA CUDA 12.1.1 with cuDNN 8 on Ubuntu 22.04 as its base image.

#### Environment Variables

You can configure GPU usage with the following environment variables:

- `OPIK_GUARDRAILS_DEVICE`: Specifies the device to use for model inference. Default is `cuda:0` if GPU is available, otherwise falls back to `cpu`.
- `CUDA_VISIBLE_DEVICES`: Controls which GPUs are visible to the container (https://docs.nvidia.com/deploy/topics/topic_5_2_1.html).

Example with custom device configuration:

```bash
docker run -p 5000:5000 --gpus all -e OPIK_GUARDRAILS_DEVICE=cuda:1 opik-guardrails-backend:latest
```

#### Automatic Fallback

The service will automatically detect if CUDA is available at startup. If no GPU is available or the NVIDIA Container Toolkit is not properly configured, it will fall back to CPU mode without requiring any configuration changes.

## API Endpoints

### Validation

This endpoint allows you to perform multiple validations on the same text in a single request.

```bash
curl -X POST http://localhost:5000/api/validate \
  -H "Content-Type: application/json" \
  -d '{
    "text": "This text is about artificial intelligence. My name is John Doe and my email is john.doe@example.com.",
    "validations": [
      {
        "type": "TOPIC",
        "config": {
          "topics": ["politics", "religion", "artificial intelligence"],
          "threshold": 0.5,
          "mode": "restrict"
        }
      },
      {
        "type": "PII",
        "config": {
          "entities": ["PERSON", "EMAIL_ADDRESS"],
          "language": "en",
          "threshold": 0.5
        }
      }
    ]
  }'
```

Parameters:
- `text`: The text to validate (required)
- `validations`: Array of validation instructions (required)
  - Each validation requires:
    - `type`: Type of validation to perform (`TOPIC` or `PII`)
    - `config`: Configuration specific to the validation type
      - For `TOPIC` type: A TopicValidationConfig object with parameters:
        - `topics`: Array of topics to check against (required)
        - `threshold`: Confidence threshold for topic detection (default: 0.5)
        - `mode`: Mode for topic matching, either "restrict" or "allow" (required)
          - `restrict`: Validation passes if NONE of the topics match (used for content filtering)
          - `allow`: Validation passes if at least one of the topics matches (used for content classification)
      - For `PII` type: A PIIValidationConfig object with parameters:
        - `entities`: Array of specific entity types to detect (if not provided, all supported entity types will be detected). Default entities include: "IP_ADDRESS", "PHONE_NUMBER", "PERSON", "MEDICAL_LICENSE", "URL", "EMAIL_ADDRESS", "IBAN_CODE". Supported entities can be found here: https://microsoft.github.io/presidio/supported_entities/
        - `language`: Language of the text (default: "en")
        - `threshold`: Confidence threshold for PII detection (default: 0.5)

Example response:
```json
{
  "validation_passed": false,
  "validations": [
    {
      "validation_passed": false,
      "type": "TOPIC",
      "validation_config": {
        "topics": ["politics", "religion", "artificial intelligence"],
        "threshold": 0.5,
        "mode": "restrict"
      },
      "validation_details": {
        "matched_topics_scores": {
          "artificial intelligence": 0.85
        },
        "scores": {
          "politics": 0.12,
          "religion": 0.05,
          "artificial intelligence": 0.85
        }
      }
    },
    {
      "validation_passed": false,
      "type": "PII",
      "validation_config": {
        "entities": ["PERSON", "EMAIL_ADDRESS"],
        "language": "en",
        "threshold": 0.5
      },
      "validation_details": {
        "detected_entities": {
          "PERSON": [
            {
              "start": 51,
              "end": 59,
              "score": 0.85,
              "text": "John Doe"
            }
          ],
          "EMAIL_ADDRESS": [
            {
              "start": 73,
              "end": 92,
              "score": 1.0,
              "text": "john.doe@example.com"
            }
          ]
        }
      }
    }
  ]
}
```

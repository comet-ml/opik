# Opik Guardrails Backend

This is the backend service for Opik Guardrails.

There are two Dockerfiles:

- `Dockerfile` (default) — GPU image built on the NVIDIA CUDA runtime. Uses the GPU
  when one is available and falls back to CPU automatically otherwise.
- `Dockerfile.cpu` — CPU-only image on a slim, multi-arch (amd64 + arm64) Python
  base. No GPU or NVIDIA Container Toolkit required, and it builds on machines
  without CUDA (e.g. Apple Silicon). Intended for local development and CPU-only
  self-hosting.

## Running as part of the Opik stack

From the repository root:

```bash
# GPU (default) — requires an NVIDIA GPU with a driver and the NVIDIA Container Toolkit
./opik.sh --guardrails

# CPU — builds Dockerfile.cpu from source, no GPU required
./opik.sh --guardrails-cpu
```

The guardrails service is then reachable through the frontend proxy at
`http://localhost:5173/guardrails`. Point the guardrails client at it with
`OPIK_GUARDRAILS_URL_OVERRIDE=http://localhost:5173/guardrails` (or leave it unset
when `url_override` already targets the local stack — the client derives the
guardrails URL from it). Add `--port-mapping` to also expose the service directly
on `http://localhost:5000`.

## Running standalone with Docker

### Prerequisites

- Docker.
- For the GPU image only: an NVIDIA GPU with a driver and the
  [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html).

### GPU mode

```bash
cd apps/opik-guardrails-backend
docker build -t opik-guardrails-backend:latest .
docker run -p 5000:5000 --gpus all opik-guardrails-backend:latest
```

### CPU mode

```bash
cd apps/opik-guardrails-backend
docker build -f Dockerfile.cpu -t opik-guardrails-backend:cpu .
docker run -p 5000:5000 opik-guardrails-backend:cpu
```

The server will be available at http://localhost:5000

### Device selection

`OPIK_GUARDRAILS_DEVICE` selects the inference device (defaults to `cuda:0` on the
GPU image, `cpu` on the CPU image). The GPU image detects at startup whether a CUDA
GPU is available and falls back to CPU when it is not, so it also runs on CPU-only
hosts. On a multi-GPU host, `CUDA_VISIBLE_DEVICES` controls which GPUs are visible
to the container.

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

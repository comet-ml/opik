# Opik Guardrails Backend

This is the backend service for Opik Guardrails.

## Running with Docker

### Prerequisites

To run the Opik Guardrails Backend with Docker, you need to have Docker installed on your system.
In addition, to use GPU acceleration, you need to have the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html) installed.

### Running the Backend

#### Option 1: Pull and run the pre-built image

```bash
docker run -p 5000:5000 --gpus all ghcr.io/comet-ml/opik/opik-guardrails-backend:latest
```

#### Option 2: Build locally (only needed once)

```bash
cd apps/opik-guardrails-backend
docker build -t opik-guardrails-backend:latest .
```

#### Option 3: Run a locally built image

```bash
docker run -p 5000:5000 --gpus all opik-guardrails-backend:latest
```

The server will be available at http://localhost:5000

## API Endpoints

### Topic Validation

```bash
curl -X POST http://localhost:5000/api/validate-topic \
  -H "Content-Type: application/json" \
  -d '{
    "text": "This text is about artificial intelligence and machine learning.",
    "config": {
      "topics": ["politics", "religion", "artificial intelligence"],
      "threshold": 0.5
    }
  }'
```

Parameters:
- `text`: The text to classify (required)
- `config`: Configuration for the topic classification (required)
  - `topics`: Array of topics to check against (required)
  - `threshold`: Confidence threshold for topic detection (default: 0.5)

Example response:
```json
{
  "validation_passed": false,
  "type": "RESTRICTED_TOPIC",
  "validation_config": {
    "topics": ["politics", "religion", "artificial intelligence"],
    "threshold": 0.5
  },
  "validation_details": {
    "matched_topics_scores": {
      "artificial intelligence": 0.92
    },
    "scores": {
      "politics": 0.12,
      "religion": 0.05,
      "artificial intelligence": 0.92
    }
  }
}
```

### PII Validation

```bash
curl -X POST http://localhost:5000/api/validate-pii \
  -H "Content-Type: application/json" \
  -d '{
    "text": "My name is John Doe and my email is john.doe@example.com",
    "config": {
      "entities": ["PERSON", "EMAIL_ADDRESS"],
      "language": "en",
      "threshold": 0.5
    }
  }'
```

Parameters:
- `text`: The text to analyze for PII (required)
- `config`: Configuration for the PII detection (required)
  - `entities`: Array of specific entity types to detect (if not provided, all supported entity types will be detected). Default entities include: "IP_ADDRESS", "PHONE_NUMBER", "PERSON", "MEDICAL_LICENSE", "URL", "EMAIL_ADDRESS", "IBAN_CODE". Supported entities can be found here: https://microsoft.github.io/presidio/supported_entities/
  - `language`: Language of the text (default: "en")
  - `threshold`: Confidence threshold for PII detection (default: 0.5)


Example response:
```json
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
          "start": 11,
          "end": 19,
          "score": 0.85,
          "text": "John Doe"
        }
      ],
      "EMAIL_ADDRESS": [
        {
          "start": 33,
          "end": 52,
          "score": 1.0,
          "text": "john.doe@example.com"
        }
      ]
    }
  }
}
```

### Combined Validation

This endpoint allows you to perform multiple validations on the same text in a single request.

```bash
curl -X POST http://localhost:5000/api/validate \
  -H "Content-Type: application/json" \
  -d '{
    "text": "This text is about artificial intelligence. My name is John Doe and my email is john.doe@example.com.",
    "validations": [
      {
        "type": "RESTRICTED_TOPIC",
        "config": {
          "topics": ["politics", "religion", "artificial intelligence"],
          "threshold": 0.5
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
    - `type`: Type of validation to perform (`RESTRICTED_TOPIC` or `PII`)
    - `config`: Configuration specific to the validation type
      - For `RESTRICTED_TOPIC` type: A RestrictedTopicValidationConfig object
      - For `PII` type: A PIIValidationConfig object

Example response:
```json
{
  "validation_passed": false,
  "validations": [
    {
      "validation_passed": false,
      "type": "RESTRICTED_TOPIC",
      "validation_config": {
        "topics": ["politics", "religion", "artificial intelligence"],
        "threshold": 0.5
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

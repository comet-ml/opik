# Opik Guardrails Backend

This is the backend service for Opik Guardrails.

## Running with Docker

```bash
# Option 1: Pull and run the pre-built image
docker run -p 5000:5000 ghcr.io/comet-ml/opik/opik-guardrails-backend:latest

# Option 2: Build locally (only needed once)
cd apps/opik-guardrails-backend
docker build -t opik-guardrails-backend:latest .

# Option 3: Run a locally built image
docker run -p 5000:5000 opik-guardrails-backend:latest
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
  "relevant_topics_scores": {
    "artificial intelligence": 0.92
  },
  "scores": {
    "politics": 0.12,
    "religion": 0.05,
    "artificial intelligence": 0.92
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
      "language": "en"
    }
  }'
```

Parameters:
- `text`: The text to analyze for PII (required)
- `config`: Configuration for the PII detection (required)
  - `entities`: Array of specific entity types to detect (if not provided, all supported entity types will be detected). Supported entities can be found here: https://microsoft.github.io/presidio/supported_entities/
  - `language`: Language of the text (default: "en")


Example response:
```json
{
  "validation_passed": false,
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
```

### Combined Validation

This endpoint allows you to perform multiple validations in a single request.

```bash
curl -X POST http://localhost:5000/api/validate \
  -H "Content-Type: application/json" \
  -d '{
    "validations": [
      {
        "type": "topic",
        "config": {
          "topics": ["politics", "religion", "artificial intelligence"],
          "threshold": 0.5
        },
        "text": "This text is about artificial intelligence and machine learning."
      },
      {
        "type": "pii",
        "config": {
          "entities": ["PERSON", "EMAIL_ADDRESS"],
          "language": "en"
        },
        "text": "My name is John Doe and my email is john.doe@example.com"
      }
    ]
  }'
```

Parameters:
- `validations`: Array of validation configurations (required)
  - Each validation requires:
    - `type`: Type of validation to perform ("topic" or "pii")
    - `config`: Configuration specific to the validation type
      - For "topic" type: A TopicClassificationConfig object
      - For "pii" type: A PIIDetectionConfig object
    - `text`: The text to validate for this specific validation

Example response:
```json
{
  "validation_passed": false,
  "validations": [
    {
      "type": "topic",
      "validation_passed": false,
      "relevant_topics_scores": {
        "politics": 0.78,
        "artificial intelligence": 0.85
      },
      "scores": {
        "politics": 0.78,
        "religion": 0.12,
        "artificial intelligence": 0.85
      }
    },
    {
      "type": "pii",
      "validation_passed": false,
      "detected_entities": {
        "PERSON": [
          {
            "start": 11,
            "end": 19,
            "score": 0.85,
            "text": "John Doe"
          }
        ]
      }
    }
  ]
}

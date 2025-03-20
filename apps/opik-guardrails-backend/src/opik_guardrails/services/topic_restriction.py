import torch
import transformers
import pydantic

from typing import Dict, List, Optional

MODEL_PATH = "facebook/bart-large-mnli"
DEVICE = "cuda:0"


class TopicsClassificationResult(pydantic.BaseModel):
    relevant_topics_scores: Dict[str, float]
    scores: Dict[str, float]


class TopicRelevanceClassifier:
    """A wrapper for the zero-shot classification model."""
    
    def __init__(self):
        self._classification_pipeline = _load_model(model_path=MODEL_PATH, device=DEVICE)
        self._default_threshold = 0.5

    def predict(self, text: str, topics: List[str], threshold: Optional[float]) -> TopicsClassificationResult:
        threshold = threshold if threshold is not None else self._default_threshold

        classification_result = self._classification_pipeline(text, topics)
        scores = {
            label: score 
            for label, score
            in zip(classification_result["labels"], classification_result["scores"])
        }

        relevant_topics_scores = {
            label: score
            for label, score 
            in scores.items()
            if score >= threshold
        }

        return TopicsClassificationResult(
            relevant_topics_scores=relevant_topics_scores,
            scores=scores,
        )


def _load_model(model_path: str, device: str) -> transformers.Pipeline:
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    torch_dtype = torch.float16 if (torch.cuda.is_available() and device != "cpu") else torch.float32

    model: torch.nn.Module = transformers.AutoModelForSequenceClassification.from_pretrained(
        model_path,
        torch_dtype=torch_dtype,
        device_map=device,
    )
    model.eval()

    tokenizer = transformers.AutoTokenizer.from_pretrained(model_path)

    classifier = transformers.pipeline(
        task="zero-shot-classification",
        model=model,
        tokenizer=tokenizer,
        multi_label=True,
    )

    return classifier
import transformers
import torch
import os


def load_pipeline() -> transformers.Pipeline:
    # TODO: make sure everything is downloaded and the pipeline is ready-to-use.

    model_path = "facebook/bart-large-mnli"

    if "OPIK_GUARDRAILS_DEVICE" in os.environ:
        device = os.getenv("OPIK_GUARDRAILS_DEVICE")

        if "cuda" in device:
            torch.cuda.empty_cache()
    elif torch.cuda.is_available():
        torch.cuda.empty_cache()
        device = "cuda:0" # Default to the first GPU by default
    elif torch.backends.mps.is_available():
        device = "mps"
    else:
        device = "cpu"

    torch_dtype = (
        torch.float16
        if (torch.cuda.is_available() and device != "cpu")
        else torch.float32
    )

    model: torch.nn.Module = (
        transformers.AutoModelForSequenceClassification.from_pretrained(
            model_path,
            torch_dtype=torch_dtype,
            device_map=device,
        )
    )
    model.eval()

    tokenizer = transformers.AutoTokenizer.from_pretrained(model_path)

    classifier = transformers.pipeline(
        task="zero-shot-classification",
        model=model,
        tokenizer=tokenizer,
        multi_label=True,
    )

    _ = classifier("This is a test warm up call", ["test"])

    return classifier

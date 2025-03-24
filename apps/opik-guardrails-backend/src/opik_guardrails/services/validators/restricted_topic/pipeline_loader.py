import transformers
import torch


def load_pipeline(model_path: str, device: str) -> transformers.Pipeline:
    # TODO: make sure everything is downloaded and the pipeline is ready-to-use.
    # Also make a first test call?

    if torch.cuda.is_available():
        torch.cuda.empty_cache()

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

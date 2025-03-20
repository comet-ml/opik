import time
import tqdm
from typing import List
import torch

import transformers

MODEL_PATH = "facebook/bart-large-mnli"
DEVICE = "cuda:0"
#DEVICE = "cpu"


def initialize_model():
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    torch_dtype = torch.float16 if (torch.cuda.is_available() and DEVICE != "cpu") else torch.float32
    
    model = transformers.AutoModelForSequenceClassification.from_pretrained(
        MODEL_PATH,
        torch_dtype=torch_dtype,
        device_map=DEVICE,
        low_cpu_mem_usage=True,
    )
    
    if torch.cuda.is_available():
        model = model.eval()
    
    tokenizer = transformers.AutoTokenizer.from_pretrained(MODEL_PATH)
    
    classifier = transformers.pipeline(
        task="zero-shot-classification",
        model=model,
        tokenizer=tokenizer,
        multi_label=True,
    )

    return classifier


classifier = initialize_model()

def measure_time_per_classification(text: str, topics: List[str], num_requests: int):
    start_time = time.time()
    results = []
    for _ in tqdm.tqdm(range(num_requests), desc="Performing classifications"):
        results.append(classifier(text, topics, multilabel=True))
    
    print(results[0]["labels"])
    print(results[0]["scores"])
    print(sum(results[0]["scores"]))

    end_time = time.time()
    total_time = end_time - start_time
    time_per_classification = total_time / num_requests
    
    return time_per_classification

if __name__ == "__main__":
    with open("/home/akuzmik/work-repos/opik/apps/opik-guardrails-backend/measurements/financial_article.txt", mode="rt") as f:
        text = f.read()
    
#     text = """
# Education and training is an important investment in you and
# your family. Investing wisely in higher education is one of the
# best financial decisions you can make. More education means
# higher earnings for life. Studies show more education leads to
# bigger paychecks. So, the more you learn, the more you earn."""

    topics = ["finance", "healthcare", "art", "history"]
    num_requests = 1
    
    throughput = measure_time_per_classification(text, topics, num_requests)
    print(f"Speed: {throughput:.2f} seconds per classification")
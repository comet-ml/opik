import torch
import transformers
from flask import Flask, jsonify, request


app = Flask(__name__)


MODEL_PATH = "facebook/bart-large-mnli"
#DEVICE = "cuda:0"
DEVICE = "cpu"


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

@app.route('/classify', methods=['POST'])
def classify():
    data = request.json
    text = data.get("text")
    topics = data.get("topics")
    
    if not text or not topics:
        return jsonify({"error": "Missing text or topic"}), 400
    
    # Perform classification
    result = classifier(text, topics)
    scores = dict(zip(result["labels"], result["scores"]))

    return jsonify({
        "text": text,
        "scores": scores
    })

if __name__ == '__main__':
    app.run(debug=False)

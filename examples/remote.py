from opik import entrypoint
import time
import random



@entrypoint(project="demo")
def run_agent(question: str,) -> str:
    print(f"[agent] Received question: {question}")
    time.sleep(0.5)

    print("[agent] Parsing intent...")
    time.sleep(0.8)
    intent = "factual_qa" if "?" in question else "open_ended"
    print(f"[agent] Detected intent: {intent}")
    time.sleep(0.3)

    print("[agent] Retrieving relevant context from knowledge base...")
    time.sleep(1.2)
    num_docs = random.randint(3, 7)
    print(f"[agent] Found {num_docs} relevant documents (similarity > 0.82)")
    time.sleep(0.4)

    for i in range(1, num_docs + 1):
        score = round(random.uniform(0.82, 0.97), 2)
        print(f"[agent]   doc_{i}: score={score}")
        time.sleep(0.15)

    print("[agent] Building prompt with retrieved context...")
    time.sleep(0.6)
    token_count = random.randint(1200, 2400)
    print(f"[agent] Prompt size: {token_count} tokens")
    time.sleep(.1)

    print("[agent] Calling LLM (gpt-4o-mini)...")
    time.sleep(2.0)
    output_tokens = random.randint(80, 250)
    print(f"[agent] LLM responded with {output_tokens} tokens")
    time.sleep(0.3)

    print("[agent] Running hallucination check...")
    time.sleep(0.9)
    hall_score = round(random.uniform(0.01, 0.15), 3)
    print(f"[agent] Hallucination score: {hall_score} (threshold: 0.3) ✓")
    time.sleep(0.2)

    print("[agent] Formatting final response...")
    time.sleep(0.4)

    answer = f"Based on {num_docs} retrieved sources, the answer to '{question}' is 42."
    print(f"[agent] Done — response length: {len(answer)} chars")
    return answer


if __name__ == "__main__":
    print(run_agent(question="What is the meaning of life?", num_docs=3))

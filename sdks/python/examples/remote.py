from opik import entrypoint
import time


@entrypoint(project="demo")
def run_agent(question: str) -> str:
    """Simple agent — no LLM, just string processing."""
    result = question.upper()
    time.sleep(10)
    return f"Processed: {result}"


if __name__ == "__main__":
    print(run_agent(question="What is 2 +2?"))

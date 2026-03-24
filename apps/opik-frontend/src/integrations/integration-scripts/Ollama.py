import ollama
from opik import configure, opik_context, track  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE


@track(tags=["ollama"])  # HIGHLIGHTED_LINE
def ollama_llm_call(user_message: str):
    # Create the Ollama model
    response = ollama.chat(
        model="llama3.1",
        messages=[
            {
                "role": "user",
                "content": user_message,
            },
        ],
    )

    opik_context.update_current_span(  # HIGHLIGHTED_LINE
        metadata={
            "model": response["model"],
            "eval_duration": response["eval_duration"],
            "load_duration": response["load_duration"],
            "prompt_eval_duration": response[
                "prompt_eval_duration"
            ],
            "prompt_eval_count": response["prompt_eval_count"],
            "done": response["done"],
            "done_reason": response["done_reason"],
        },
        usage={
            "completion_tokens": response["eval_count"],
            "prompt_tokens": response["prompt_eval_count"],
            "total_tokens": response["eval_count"]
            + response["prompt_eval_count"],
        },
    )
    return response["message"]


ollama_llm_call("Say this is a test")

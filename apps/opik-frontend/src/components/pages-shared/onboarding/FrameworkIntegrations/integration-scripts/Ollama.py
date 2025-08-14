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
        metadata={  # HIGHLIGHTED_LINE
            "model": response["model"],  # HIGHLIGHTED_LINE
            "eval_duration": response["eval_duration"],  # HIGHLIGHTED_LINE
            "load_duration": response["load_duration"],  # HIGHLIGHTED_LINE
            "prompt_eval_duration": response[
                "prompt_eval_duration"
            ],  # HIGHLIGHTED_LINE
            "prompt_eval_count": response["prompt_eval_count"],  # HIGHLIGHTED_LINE
            "done": response["done"],  # HIGHLIGHTED_LINE
            "done_reason": response["done_reason"],  # HIGHLIGHTED_LINE
        },
        usage={  # HIGHLIGHTED_LINE
            "completion_tokens": response["eval_count"],  # HIGHLIGHTED_LINE
            "prompt_tokens": response["prompt_eval_count"],  # HIGHLIGHTED_LINE
            "total_tokens": response["eval_count"]
            + response["prompt_eval_count"],  # HIGHLIGHTED_LINE
        },  # HIGHLIGHTED_LINE
    )  # HIGHLIGHTED_LINE
    return response["message"]


ollama_llm_call("Say this is a test")

import uuid
import dspy


class State(dict):
    def __getattr__(self, key):
        try:
            return self[key]
        except KeyError as e:
            raise AttributeError(e)

    def __setattr__(self, key, value):
        self[key] = value

    def __delattr__(self, key):
        try:
            del self[key]
        except KeyError as e:
            raise AttributeError(e)


def create_dspy_signature(
    input: str,
    output: str,
    prompt: str = None,
):
    """
    Create a dspy Signature given inputs, outputs, prompt
    """
    attributes = {
        "__doc__": prompt,
        "__annotations__": {},
    }
    attributes["__annotations__"][input] = str
    attributes[input] = dspy.InputField(desc="")
    attributes["__annotations__"][output] = str
    attributes[output] = dspy.OutputField(desc="")
    return type("MySignature", (dspy.Signature,), attributes)


def opik_metric_to_dspy(metric, output):
    answer_field = output

    def opik_metric_score_wrapper(example, prediction, trace=None):
        result = getattr(metric, "score")(
            output=getattr(prediction, answer_field),
            reference=getattr(example, answer_field),
        )
        return result.value

    return opik_metric_score_wrapper


def create_dspy_training_set(data: list[dict], input: str) -> list[dspy.Example]:
    """
    Turn a list of dicts into a list of dspy Examples
    """
    output = []
    for example in data:
        example_obj = dspy.Example(
            **example, dspy_uuid=str(uuid.uuid4()), dspy_split="train"
        )
        example_obj = example_obj.with_inputs(input)
        output.append(example_obj)
    return output

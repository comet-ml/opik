import dspy

# Tools


def search_wikipedia(type: str, query: str) -> list[str]:
    """Given a type and query, search wikipedia"""
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [x["text"] for x in results]


def evaluate_math(type: str, value: str) -> float:
    """Given a string, evaluate it as a Python Expression and return a float"""
    result = float(eval(value))
    return result
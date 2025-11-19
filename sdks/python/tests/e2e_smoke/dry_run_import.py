import opik

project_name = "e2e-smoke-test-project"


@opik.track(
    tags=["inner-tag1", "inner-tag2"],
    metadata={"inner-metadata-key": "inner-metadata-value"},
    project_name=project_name,
)
def hello(x: str) -> str:
    result = f"Hello {x}!"
    print(result)
    return result


def main():
    hello("World")


if __name__ == "__main__":
    main()

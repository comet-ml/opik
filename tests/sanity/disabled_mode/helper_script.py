import os
import unittest.mock

import requests

requests.request = unittest.mock.Mock()

os.environ["COMET_API_KEY"] = "fake-api-key"
os.environ["COMET_DISABLE"] = "1"


def run_disabled_api():
    import comet_llm

    comet_llm.log_prompt("prompt", "output")

    comet_llm.start_chain(
        inputs="the-inputs",
    )

    with comet_llm.Span(inputs={"x1": "y1"}, category="z1") as span1:
        with comet_llm.Span(inputs={"x2": "y2"}, category="z2") as span3:
            span3.set_outputs({"x3": "y3"}, metadata={"z3": "z3"})
        span1.set_outputs({"x4": "y4"})

    comet_llm.end_chain({"x5": "y5"}, metadata={"z5": "z5"})


def main():
    run_disabled_api()
    requests.request.assert_not_called()


if __name__ == '__main__':
    main()
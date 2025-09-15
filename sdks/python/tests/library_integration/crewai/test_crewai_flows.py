import opik
from opik.integrations.crewai import track_crewai

from crewai.flow.flow import Flow, start, listen

from ...testlib import (
    ANY_BUT_NONE,
    ANY_STRING,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)
from . import constants


class _ExampleFlow(Flow):
    model = constants.MODEL_NAME_SHORT

    @start()
    def generate_city(self):
        # Minimal flow step that triggers an LLM call via litellm
        from litellm import completion

        response = completion(
            model=self.model,
            messages=[
                {
                    "role": "user",
                    "content": "Return the name of a random city in the world.",
                }
            ],
        )

        return response["choices"][0]["message"]["content"]

    @listen(generate_city)
    def generate_fun_fact(self, random_city):
        from litellm import completion

        response = completion(
            model=self.model,
            messages=[
                {
                    "role": "user",
                    "content": f"Tell me a fun fact about {random_city}",
                }
            ],
        )

        return response["choices"][0]["message"]["content"]


def test_crewai_flows__simple_flow__llm_call_logged(fake_backend):
    project_name = "crewai-flows-test"

    track_crewai(project_name=project_name)

    flow = _ExampleFlow()
    _ = flow.kickoff()
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        end_time=ANY_BUT_NONE,
        id=ANY_STRING,
        input=ANY_DICT,
        metadata={"created_from": "crewai"},
        name="kickoff_async",
        output=ANY_DICT,
        project_name=project_name,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        tags=["crewai"],
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING,
                input=ANY_DICT,
                metadata={"created_from": "crewai"},
                name="kickoff_async",
                output=ANY_DICT,
                project_name=project_name,
                start_time=ANY_BUT_NONE,
                tags=["crewai"],
                type="general",
                spans=[
                    # First flow method - generate_city
                    SpanModel(
                        end_time=ANY_BUT_NONE,
                        id=ANY_STRING,
                        input=ANY_DICT,
                        metadata={"created_from": "crewai"},
                        name="generate_city",
                        output=ANY_DICT,
                        project_name=project_name,
                        start_time=ANY_BUT_NONE,
                        tags=["crewai"],
                        spans=[
                            SpanModel(
                                end_time=ANY_BUT_NONE,
                                id=ANY_STRING,
                                input=ANY_DICT,
                                metadata={
                                    "created_from": "crewai",
                                    "usage": ANY_DICT,
                                },
                                model=ANY_STRING,
                                name="llm call",
                                output=ANY_DICT,
                                project_name=project_name,
                                provider="openai",
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                type="llm",
                                usage=constants.EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                                spans=[],
                            )
                        ],
                    ),
                    # Second flow method - generate_fun_fact
                    SpanModel(
                        end_time=ANY_BUT_NONE,
                        id=ANY_STRING,
                        input=ANY_DICT,
                        metadata={"created_from": "crewai"},
                        name="generate_fun_fact",
                        output=ANY_DICT,
                        project_name=project_name,
                        start_time=ANY_BUT_NONE,
                        tags=["crewai"],
                        spans=[
                            SpanModel(
                                end_time=ANY_BUT_NONE,
                                id=ANY_STRING,
                                input=ANY_DICT,
                                metadata={
                                    "created_from": "crewai",
                                    "usage": ANY_DICT,
                                },
                                model=ANY_STRING,
                                name="llm call",
                                output=ANY_DICT,
                                project_name=project_name,
                                provider="openai",
                                start_time=ANY_BUT_NONE,
                                tags=["crewai"],
                                type="llm",
                                usage=constants.EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                                spans=[],
                            )
                        ],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

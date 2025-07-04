import google.adk.agents
import google.adk.tools.agent_tool
from opik.integrations.adk import build_mermaid


def test_build_mermaid__simple_agent():
    agent = google.adk.agents.LlmAgent(
        name="test_agent",
        model="gemini-2.0-flash",
        instruction="test instruction",
        description="test description",
    )

    graph = build_mermaid(agent)
    assert graph == ("flowchart LR\n" 'test_agent["test_agent"]')


def test_build_mermaid__root_sequential_agent_with_llm_subagents_having_their_own_subagents():
    agent = google.adk.agents.SequentialAgent(
        name="SequentialAgent",
        sub_agents=[
            google.adk.agents.LlmAgent(
                name="LLMAgent1",
                model="gemini-2.0-flash",
                instruction="test instruction",
                description="test description",
                sub_agents=[
                    google.adk.agents.LlmAgent(
                        name="LLMAgent1_SubAgent",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                    ),
                ],
            ),
            google.adk.agents.LlmAgent(
                name="LLMAgent2",
                model="gemini-2.0-flash",
                instruction="test instruction",
                description="test description",
                sub_agents=[
                    google.adk.agents.LlmAgent(
                        name="LLMAgent2_SubAgent",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                    ),
                ],
            ),
        ],
    )

    graph = build_mermaid(agent)
    assert (
        graph
        == """flowchart LR
SequentialAgent["SequentialAgent"]
subgraph SequentialAgent["SequentialAgent"]
  LLMAgent1 --> LLMAgent2
end
LLMAgent1 --> LLMAgent1_SubAgent
LLMAgent2 --> LLMAgent2_SubAgent"""
    )


def test_build_mermaid__root_parallel_agent_with_llm_subagents_having_their_own_subagents():
    agent = google.adk.agents.ParallelAgent(
        name="ParallelAgent",
        sub_agents=[
            google.adk.agents.LlmAgent(
                name="LLMAgent1",
                model="gemini-2.0-flash",
                instruction="test instruction",
                description="test description",
                sub_agents=[
                    google.adk.agents.LlmAgent(
                        name="LLMAgent1_SubAgent",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                    ),
                ],
            ),
            google.adk.agents.LlmAgent(
                name="LLMAgent2",
                model="gemini-2.0-flash",
                instruction="test instruction",
                description="test description",
                sub_agents=[
                    google.adk.agents.LlmAgent(
                        name="LLMAgent2_SubAgent",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                    ),
                ],
            ),
        ],
    )

    graph = build_mermaid(agent)
    assert (
        graph
        == """flowchart LR
ParallelAgent["ParallelAgent"]
subgraph ParallelAgent["ParallelAgent"]
  LLMAgent1["LLMAgent1"]
  LLMAgent2["LLMAgent2"]
end
LLMAgent1 --> LLMAgent1_SubAgent
LLMAgent2 --> LLMAgent2_SubAgent"""
    )


def test_build_mermaid__root_loop_agent_with_llm_subagent():
    agent = google.adk.agents.LoopAgent(
        name="LoopAgent",
        sub_agents=[
            google.adk.agents.LlmAgent(
                name="LLMAgent1",
                model="gemini-2.0-flash",
                instruction="test instruction",
                description="test description",
            ),
        ],
    )

    graph = build_mermaid(agent)
    assert (
        graph
        == """flowchart LR
LoopAgent["LoopAgent"]
subgraph LoopAgent["LoopAgent"]
  LLMAgent1
  LLMAgent1 -.->|repeat| LLMAgent1
end"""
    )


def test_build_mermaid__complex_agent_tree_with_sequential_parallel_and_llm_agents():
    agent = google.adk.agents.LlmAgent(
        name="RootLLMAgent",
        model="gemini-2.0-flash",
        instruction="test instruction",
        description="test description",
        sub_agents=[
            google.adk.agents.LlmAgent(
                name="RootLLMAgent_SubAgent",
                model="gemini-2.0-flash",
                instruction="test instruction",
                description="test description",
                sub_agents=[
                    google.adk.agents.SequentialAgent(
                        name="RootLLMAgent_SubAgent_SequentialSubAgent",
                        sub_agents=[
                            google.adk.agents.LlmAgent(
                                name="RootLLMAgent_SubAgent_SequentialSubAgent_LLMSubAgent1",
                                model="gemini-2.0-flash",
                                instruction="test instruction",
                                description="test description",
                            ),
                        ],
                    ),
                ],
            ),
            google.adk.agents.ParallelAgent(
                name="RootLLMAgent_ParallelSubAgent",
                sub_agents=[
                    google.adk.agents.LlmAgent(
                        name="RootLLMAgent_ParallelSubAgent_LLMSubAgent1",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                    ),
                    google.adk.agents.LlmAgent(
                        name="RootLLMAgent_ParallelSubAgent_LLMSubAgent2",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                    ),
                ],
            ),
            google.adk.agents.SequentialAgent(
                name="RootLLMAgent_SequentialSubAgent",
                sub_agents=[
                    google.adk.agents.LlmAgent(
                        name="RootLLMAgent_SequentialSubAgent_LLMSubAgent1",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                        sub_agents=[
                            google.adk.agents.LlmAgent(
                                name="RootLLMAgent_SequentialSubAgent_LLMSubAgent1_LLMSubAgent1",
                                model="gemini-2.0-flash",
                                instruction="test instruction",
                                description="test description",
                            ),
                        ],
                    ),
                    google.adk.agents.LlmAgent(
                        name="RootLLMAgent_SequentialSubAgent_LLMSubAgent2",
                        model="gemini-2.0-flash",
                        instruction="test instruction",
                        description="test description",
                        sub_agents=[
                            google.adk.agents.LlmAgent(
                                name="RootLLMAgent_SequentialSubAgent_LLMSubAgent2_LLMSubAgent1",
                                model="gemini-2.0-flash",
                                instruction="test instruction",
                                description="test description",
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )

    graph = build_mermaid(agent)
    assert (
        graph
        == """flowchart LR
RootLLMAgent["RootLLMAgent"]
subgraph RootLLMAgent_SubAgent_SequentialSubAgent["RootLLMAgent_SubAgent_SequentialSubAgent"]
  RootLLMAgent_SubAgent_SequentialSubAgent_LLMSubAgent1
end
subgraph RootLLMAgent_ParallelSubAgent["RootLLMAgent_ParallelSubAgent"]
  RootLLMAgent_ParallelSubAgent_LLMSubAgent1["RootLLMAgent_ParallelSubAgent_LLMSubAgent1"]
  RootLLMAgent_ParallelSubAgent_LLMSubAgent2["RootLLMAgent_ParallelSubAgent_LLMSubAgent2"]
end
subgraph RootLLMAgent_SequentialSubAgent["RootLLMAgent_SequentialSubAgent"]
  RootLLMAgent_SequentialSubAgent_LLMSubAgent1 --> RootLLMAgent_SequentialSubAgent_LLMSubAgent2
end
RootLLMAgent --> RootLLMAgent_SubAgent
RootLLMAgent --> RootLLMAgent_ParallelSubAgent
RootLLMAgent --> RootLLMAgent_SequentialSubAgent
RootLLMAgent_SubAgent --> RootLLMAgent_SubAgent_SequentialSubAgent
RootLLMAgent_SequentialSubAgent_LLMSubAgent1 --> RootLLMAgent_SequentialSubAgent_LLMSubAgent1_LLMSubAgent1
RootLLMAgent_SequentialSubAgent_LLMSubAgent2 --> RootLLMAgent_SequentialSubAgent_LLMSubAgent2_LLMSubAgent1"""
    )


def test_build_mermaid__root_llm_agent_with_subagents_and_tools_and_agent_tools():
    def some_func_tool(input: str) -> str:
        return "something"

    agent = google.adk.agents.LlmAgent(
        name="LLMAgent1",
        model="gemini-2.0-flash",
        instruction="test instruction",
        description="test description",
        sub_agents=[
            google.adk.agents.LlmAgent(
                name="LLMAgent1_SubAgent",
                model="gemini-2.0-flash",
                instruction="test instruction",
                description="test description",
                tools=[some_func_tool],
            ),
        ],
        tools=[
            google.adk.tools.agent_tool.AgentTool(
                agent=google.adk.agents.LlmAgent(
                    name="LLMAgent1_AgentTool1",
                    model="gemini-2.0-flash",
                    instruction="test instruction",
                    description="test description",
                    sub_agents=[
                        google.adk.agents.LlmAgent(
                            name="LLMAgent1_AgentTool1_SubAgent",
                            model="gemini-2.0-flash",
                            instruction="test instruction",
                            description="test description",
                        ),
                    ],
                ),
            ),
            some_func_tool,
        ],
    )

    graph = build_mermaid(agent)
    assert (
        graph
        == """flowchart LR
LLMAgent1["LLMAgent1"]
LLMAgent1 --> LLMAgent1_SubAgent
LLMAgent1 --> LLMAgent1_AgentTool1
LLMAgent1 --> some_func_tool
LLMAgent1_SubAgent --> some_func_tool
LLMAgent1_AgentTool1 --> LLMAgent1_AgentTool1_SubAgent"""
    )

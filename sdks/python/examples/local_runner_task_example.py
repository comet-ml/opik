"""
Example: Run an EvaluationSuite against a local runner agent using LocalRunnerTask.

Prerequisites:
  1. A local Opik backend running (or cloud).
  2. A project called "LangGraph Example" with a registered agent.
  3. A local runner connected to that project.

Usage:
  python examples/local_runner_task_example.py
"""

from opik import Opik, Prompt, LocalRunnerTask
from opik.api_objects.dataset.execution_policy import ExecutionPolicy

SUITE_NAME = "Research Agent Evaluation"
PROJECT_NAME = "LangGraph Example"
AGENT_NAME = "research_agent"  # <-- change to match your registered agent name


def _build_suite(client: Opik):
    HARD_POLICY = ExecutionPolicy(runs_per_item=3, pass_threshold=2)

    suite = client.create_evaluation_suite(
        name=SUITE_NAME,
        description="Research agent evaluation (10 topics, report quality assertions)",
        execution_policy=ExecutionPolicy(runs_per_item=1, pass_threshold=1),
    )

    # =====================================================================
    # EASY (3 items) — supported topics with basic quality checks
    # =====================================================================

    suite.add_item(
        data={"topic": "AI in Education"},
        assertions=[
            "The report covers key applications of AI in education such as tutoring or grading",
            "The report includes statistics or data points about adoption rates",
            "The report is structured with clear sections using markdown headers",
        ],
    )

    suite.add_item(
        data={"topic": "Climate Change"},
        assertions=[
            "The report discusses the impact of climate change on ecosystems or sea levels",
            "The report mentions carbon emissions or renewable energy",
            "The report is well-structured with multiple sections",
        ],
    )

    suite.add_item(
        data={"topic": "Quantum Computing"},
        assertions=[
            "The report explains qubit-based computation or quantum superposition",
            "The report discusses potential applications such as cryptography or drug discovery",
        ],
    )

    # =====================================================================
    # MEDIUM (4 items) — quality and depth assertions
    # =====================================================================

    suite.add_item(
        data={"topic": "Cybersecurity Threats"},
        assertions=[
            "The report provides specific numerical statistics on cyberattack frequency or cost",
            "The report covers at least three distinct threat categories (e.g., ransomware, phishing, supply-chain attacks)",
            "The report includes a section on defensive strategies or best practices",
        ],
    )

    suite.add_item(
        data={"topic": "Space Exploration"},
        assertions=[
            "The report discusses both governmental (NASA, ESA) and commercial (SpaceX, Blue Origin) programs",
            "The report mentions specific missions, timelines, or budget figures",
            "The report covers scientific objectives alongside technological challenges",
        ],
    )

    suite.add_item(
        data={"topic": "Gene Editing and CRISPR"},
        assertions=[
            "The report explains the CRISPR-Cas9 mechanism at a high level",
            "The report discusses ethical considerations or regulatory frameworks",
            "The report provides a balanced view covering medical benefits and societal risks",
        ],
    )

    suite.add_item(
        data={"topic": "Remote Work and Productivity"},
        assertions=[
            "The report is written in a professional, publication-ready tone",
            "The report flows logically from introduction through analysis to conclusion",
            "The report synthesizes data on productivity outcomes rather than just listing anecdotes",
        ],
    )

    # =====================================================================
    # HARD (3 items) — deeper quality, runs_per_item=3 for stochastic checks
    # =====================================================================

    suite.add_item(
        data={"topic": "Global Food Security"},
        assertions=[
            "The report provides actionable policy recommendations for improving food supply chains",
            "The report connects agricultural trends to broader socioeconomic implications",
            "The report discusses both short-term crises and long-term sustainability strategies",
            "The report maintains a consistent voice and writing quality throughout",
        ],
        execution_policy=HARD_POLICY,
    )

    suite.add_item(
        data={"topic": "Ocean Plastic Pollution"},
        assertions=[
            "The report contextualizes data within global targets (e.g., UN Clean Seas initiative or projected 2050 levels)",
            "The report discusses the role of multiple sectors (packaging, fishing, textiles)",
            "The report identifies innovation opportunities alongside environmental challenges",
            "The report uses evidence-based reasoning rather than generic statements",
        ],
        execution_policy=HARD_POLICY,
    )

    suite.add_item(
        data={"topic": "Mental Health in the Digital Age"},
        assertions=[
            "The report makes a compelling case for addressing digital well-being without being alarmist",
            "The report connects screen-time and social media impacts to measurable mental health outcomes",
            "The report provides a clear narrative arc from problem identification to potential interventions",
        ],
        execution_policy=HARD_POLICY,
    )

    return suite


def main():
    client = Opik(_show_misconfiguration_message=False)

    # ── 1. Look up the project ──
    api = client.rest_client
    projects = api.projects.find_projects(name=PROJECT_NAME)
    project_id = projects.content[0].id
    print(f"Project ID: {project_id}")

    # ── 2. Get agent config & create a comedian mask ──
    agent_cfg = client.get_agent_config(project_name=PROJECT_NAME)

    comedian_prompt = Prompt(
        name="Writer System Prompt",
        prompt="""You are a hilarious stand-up comedian who also happens to be a technical writer.
Your task is to write comprehensive reports that are FUNNY and entertaining.

When creating a report:
- Write like you're doing a stand-up set about the topic
- Use jokes, puns, and comedic timing
- Include actual facts but make them funny
- Use exaggeration and wit for effect
- Still structure information logically with sections
- Use markdown formatting for structure

Make the audience laugh while they learn!""",
    )
    print(f"Comedian prompt commit: {comedian_prompt.commit}")

    mask_id = agent_cfg.create_mask(
        parameters={"AgentConfig.writer_system_prompt": comedian_prompt.commit},
        description="comedian writer style",
    )
    print(f"Mask ID: {mask_id}")

    # ── 3. Get or build the evaluation suite ──
    try:
        suite = client.get_evaluation_suite(name=SUITE_NAME)
        print(f"Suite '{SUITE_NAME}' already exists, reusing it")
    except Exception:
        suite = _build_suite(client)
        print(f"Suite '{SUITE_NAME}' created with {len(suite.get_items())} items")

    # ── 4. Create the task backed by the local runner ──
    task = LocalRunnerTask(
        project_name=PROJECT_NAME,
        agent_name=AGENT_NAME,
        mask_id=mask_id,
        timeout_seconds=300,  # agents may take a while
    )

    # ── 5. Run the evaluation suite ──
    print("Running evaluation suite...")
    result = suite.run(
        task=task,
        experiment_name_prefix="comedian-local-runner",
        project_name=PROJECT_NAME
    )

    # ── 6. Print results ──
    print("\n=== Evaluation Suite Results ===")
    print(f"  Pass rate: {result.pass_rate:.1%}")
    print(f"  Items passed: {result.items_passed}/{result.items_total}")
    print(f"  Experiment: {result.experiment_name}")


if __name__ == "__main__":
    main()

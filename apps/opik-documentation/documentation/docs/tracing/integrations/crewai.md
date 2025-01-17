---
sidebar_label: CrewAI
description: Describes how to track CrewAI calls using Opik
---

# CrewAI

[CrewAI](https://www.crewai.com/) is a cutting-edge framework for orchestrating autonomous AI agents.

Opik integrates with CrewAI to log traces for all CrewAI activity.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/crewai.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting started

First, ensure you have both `opik` and `crewai` installed:

```bash
pip install opik crewai crewai-tools
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

## Logging CrewAI calls

To log a CrewAI pipeline run, you can use the [`track_crewai`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/crewai/track_crewai.html). This callback will log each CrewAI call to Opik:

```python
from crewai import Agent, Crew, Task, Process


class YourCrewName:
    def agent_one(self) -> Agent:
        return Agent(
            role="Data Analyst",
            goal="Analyze data trends in the market",
            backstory="An experienced data analyst with a background in economics",
            verbose=True,
        )

    def agent_two(self) -> Agent:
        return Agent(
            role="Market Researcher",
            goal="Gather information on market dynamics",
            backstory="A diligent researcher with a keen eye for detail",
            verbose=True
        )

    def task_one(self) -> Task:
        return Task(
            name="Collect Data Task",
            description="Collect recent market data and identify trends.",
            expected_output="A report summarizing key trends in the market.",
            agent=self.agent_one()
        )

    def task_two(self) -> Task:
        return Task(
            name="Market Research Task",
            description="Research factors affecting market dynamics.",
            expected_output="An analysis of factors influencing the market.",
            agent=self.agent_two()
        )

    def crew(self) -> Crew:
        return Crew(
            agents=[self.agent_one(), self.agent_two()],
            tasks=[self.task_one(), self.task_two()],
            process=Process.sequential,
            verbose=True
        )

    
from opik.integrations.crewai import track_crewai

track_crewai(project_name="crewai-integration-demo")

my_crew = YourCrewName().crew()
result = my_crew.kickoff()

print(result)
```

Each run will now be logged to the Opik platform:

![CrewAI](/img/cookbook/crewai_trace_cookbook.png)

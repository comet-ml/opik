# Using Opik with CrewAI

This notebook showcases how to use Opik with CrewAI. [CrewAI](https://github.com/crewAIInc/crewAI) is a cutting-edge framework for orchestrating autonomous AI agents.
> CrewAI enables you to create AI teams where each agent has specific roles, tools, and goals, working together to accomplish complex tasks.

> Think of it as assembling your dream team - each member (agent) brings unique skills and expertise, collaborating seamlessly to achieve your objectives.

For this guide we will use CrewAI's quickstart example.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=llamaindex&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&=opik&utm_medium=colab&utm_content=llamaindex&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=llamaindex&utm_campaign=opik) for more information.


```python
%pip install crewai crewai-tools opik --upgrade
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we set up our API keys for our LLM-provider as environment variables:


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

## Using CrewAI
The first step is to create our project. We will use an example from CrewAI's documentation:


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
            verbose=True,
        )

    def task_one(self) -> Task:
        return Task(
            name="Collect Data Task",
            description="Collect recent market data and identify trends.",
            expected_output="A report summarizing key trends in the market.",
            agent=self.agent_one(),
        )

    def task_two(self) -> Task:
        return Task(
            name="Market Research Task",
            description="Research factors affecting market dynamics.",
            expected_output="An analysis of factors influencing the market.",
            agent=self.agent_two(),
        )

    def crew(self) -> Crew:
        return Crew(
            agents=[self.agent_one(), self.agent_two()],
            tasks=[self.task_one(), self.task_two()],
            process=Process.sequential,
            verbose=True,
        )
```

Now we can import Opik's tracker and run our `crew`:


```python
from opik.integrations.crewai import track_crewai

track_crewai(project_name="crewai-integration-demo")

my_crew = YourCrewName().crew()
result = my_crew.kickoff()

print(result)
```

You can now go to the Opik app to see the trace:

![CrewAI trace in Opik](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/crewai_trace_cookbook.png)



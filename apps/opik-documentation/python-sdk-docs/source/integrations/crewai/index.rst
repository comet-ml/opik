CrewAI
=======

Opik integrates with CrewAI to allow you to log your CrewAI activities and LLM calls to the Opik platform, simply invoke `track_crewai` to start logging::

    from opik.integrations.crewai import track_crewai
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

    track_crewai(project_name="crewai-integration-demo")

    my_crew = YourCrewName().crew()
    result = my_crew.kickoff()

    print(result)


You can learn more about the `track_crewai` decorator in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   track_crewai

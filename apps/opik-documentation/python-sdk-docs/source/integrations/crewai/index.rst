CrewAI
=======

Opik integrates with CrewAI to allow you to log your CrewAI activities and LLM calls to the Opik platform, simply invoke `track_crewai` to start logging::

    from crewai import Agent, Crew, Process, Task
    from crewai.project import CrewBase, agent, crew, task

    from opik.integrations.crewai import track_crewai


    @CrewBase
    class LatestAiDevelopmentCrew:
        """LatestAiDevelopment crew"""

        @agent
        def researcher(self) -> Agent:
            return Agent(
                role="{topic} Senior Data Researcher",
                goal="Conduct thorough research on given topics",
                backstory="Expert researcher with years of experience in data analysis",
                verbose=True,
            )

        @agent
        def reporting_analyst(self) -> Agent:
            return Agent(
                role="{topic} Reporting Analyst",
                goal="Create detailed reports based on {topic} data analysis and research findings",
                backstory="You're a meticulous analyst with a keen eye for detail. You're known for your ability to turn complex data into clear and concise reports, making it easy for others to understand and act on the information you provide.",
                verbose=True,
            )

        @task
        def research_task(self) -> Task:
            return Task(
                description="Research the latest developments in AI technology",
                expected_output="A comprehensive report on AI advancements",
                agent=self.research_agent(),
                output_file="output/research.md"
            )

        @task
        def reporting_task(self) -> Task:
            return Task(
                description="Review the context you got and expand each topic into a small section for a report. Make sure the report is detailed and contains any and all relevant information.",
                expected_output="A fully fledge reports with the mains topics, each with a small section of information. Formatted as markdown without ```",
                agent=self.research_agent(),
                output_file='output/report.md'  # This is the file that will be contain the final report.
            )

        @crew
        def crew(self) -> Crew:
            """Creates the LatestAiDevelopment crew"""
            return Crew(
                agents=self.agents,  # Automatically created by the @agent decorator
                tasks=self.tasks,  # Automatically created by the @task decorator
                process=Process.sequential,
                verbose=True,
            )


    track_crewai(project_name="crewai-integration-demo")

    inputs = {
        'topic': 'AI Agents'
    }
    my_crew = LatestAiDevelopmentCrew()
    my_crew = my_crew.crew()
    my_crew = my_crew.kickoff(inputs=inputs)

    print(my_crew)




You can learn more about the `track_crewai` decorator in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   track_crewai

from dotenv import load_dotenv
load_dotenv(".env.local")

import opik
from dataclasses import dataclass, field
from typing import TypedDict, Annotated

from langgraph.graph import StateGraph, END
from langgraph.prebuilt import ToolNode
from opik.integrations.langchain import OpikTracer
from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage, AIMessage, BaseMessage
from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field

from flask import Flask, jsonify, request
from opik_config import Prompt, experiment_context, agent_config


# ============================================================================
# Configuration
# ============================================================================

@agent_config
@dataclass
class AgentConfig:
    """Configuration for the research agent with all prompts."""
    
    # Researcher prompts
    researcher_system_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Researcher System Prompt",
        prompt="""You are an expert research analyst with access to a research data tool.

Your task:
1. Use the fetch_research_data tool to get detailed information about the topic
2. Analyze the data you receive from the tool
3. Create comprehensive research notes that include:
   - Key insights and trends
   - Important statistics and facts
   - Organized information
   - Significant findings or patterns

Always call the fetch_research_data tool first to get the data, then analyze it thoroughly."""
    ))
    
    researcher_user_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Researcher User Prompt",
        prompt="""Please research the following topic: {topic}

Use the fetch_research_data tool to get information, then create comprehensive research notes based on the data you receive."""
    ))
    
    # Writer prompts
    writer_system_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Writer System Prompt",
        prompt="""You are a skilled technical writer. Your task is to write comprehensive, well-structured reports.

When creating a report:
- Use clear, professional language
- Structure information logically with sections
- Incorporate both research notes and raw data
- Provide context and explanations
- Make the report engaging and informative
- Use markdown formatting for structure

Create polished, publication-ready reports."""
    ))
    
    writer_user_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Writer User Prompt",
        prompt="""Write a comprehensive report on: {topic}

Research Notes:
{research_notes}

Raw Data:
{data}

Please create a well-structured, detailed report incorporating all the information above."""
    ))


config = AgentConfig()


# ============================================================================
# State Definition
# ============================================================================

class ResearchState(TypedDict):
    """State that flows through the research workflow."""
    topic: str
    messages: Annotated[list[BaseMessage], "The messages in the conversation"]
    research_notes: str
    final_report: str


# ============================================================================
# Internal Tool - Data Fetching (Explicit Tool Definition)
# ============================================================================

class FetchResearchDataInput(BaseModel):
    """Input schema for fetch_research_data tool."""
    topic: str = Field(
        description="The research topic to fetch data for. Examples: 'AI in Education', 'Climate Change'"
    )


def _fetch_research_data_impl(topic: str) -> str:
    """
    Implementation of the research data fetching tool.
    Returns mock data for supported topics.
    
    Args:
        topic: The research topic
        
    Returns:
        Formatted research data string
    """
    # Mock data repository
    mock_data = {
        "AI in Education": """
# AI in Education - Research Data

## Market Growth
- Global EdTech market growing at 45% annually
- AI in education market expected to reach $20B by 2027
- 86% of educational institutions adopting AI tools

## Key Applications
1. Personalized Learning
   - Adaptive learning platforms adjust to student pace
   - 73% improvement in student engagement reported
   - AI tutors available 24/7

2. Automated Grading
   - Reduces teacher workload by 30%
   - Instant feedback for students
   - Consistent evaluation criteria

3. Intelligent Content Creation
   - AI-generated practice problems
   - Customized study materials
   - Multi-language support

## Current Trends
- Virtual reality classrooms
- Predictive analytics for student performance
- Natural language processing for essay evaluation
- Gamification with AI-driven challenges

## Challenges
- Data privacy concerns
- Digital divide and accessibility
- Teacher training requirements
- Cost of implementation

## Statistics
- 92% of teachers report time savings with AI tools
- Student test scores improved by average of 18%
- 67% of students prefer AI-enhanced learning
""",
        "Climate Change": """
# Climate Change - Research Data

## Global Temperature Trends
- Average global temperature increased 1.1°C since pre-industrial times
- Last decade was the warmest on record
- Arctic warming twice as fast as global average

## Key Impacts
1. Sea Level Rise
   - Global sea levels rising 3.3mm per year
   - Projected 0.3-1.0m rise by 2100
   - 200+ million people at risk in coastal areas

2. Extreme Weather Events
   - 40% increase in severe weather events since 1980
   - Economic damages exceeding $150B annually
   - More frequent droughts and floods

3. Ecosystem Changes
   - 25% of species face extinction risk
   - Coral reef bleaching affecting 70% of reefs
   - Shifting migration patterns

## Carbon Emissions
- Global CO2 emissions: 36.4 billion tons/year
- Transportation: 24% of emissions
- Energy production: 42% of emissions
- Need 45% reduction by 2030 for 1.5°C target

## Solutions in Progress
- Renewable energy adoption growing 20% annually
- Electric vehicle sales doubled in 2 years
- Carbon capture technology advancing
- Reforestation initiatives

## Economic Impact
- Climate disasters cost $280B in 2022
- Green economy creating 24M new jobs by 2030
- Renewable energy now cheaper than fossil fuels
"""
    }
    
    # Return mock data or a default message
    topic_lower = topic.lower()
    for key, data in mock_data.items():
        if key.lower() in topic_lower or topic_lower in key.lower():
            print(f"[Tool] Fetched research data for: {topic}")
            return data
    
    # Default response for unsupported topics
    return f"""
# {topic} - Research Data

No specific mock data available for this topic.

Please use one of the supported topics:
- AI in Education
- Climate Change

For demonstration purposes, here's generic information:
- This is a complex and evolving field
- Multiple stakeholders and perspectives involved
- Requires interdisciplinary approach
- Current research is ongoing
"""


# Create the explicit tool definition
fetch_research_data = StructuredTool(
    name="fetch_research_data",
    description="""Fetch comprehensive research data for a given topic. 
    
This tool provides detailed information including:
- Market statistics and growth trends
- Key applications and use cases
- Current trends and challenges
- Supporting data and facts

Use this tool whenever you need to gather information about a research topic before analyzing it.""",
    func=_fetch_research_data_impl,
    args_schema=FetchResearchDataInput,
)


# ============================================================================
# Agent Nodes
# ============================================================================

def researcher_agent(state: ResearchState) -> ResearchState:
    """
    Researcher agent: Uses LLM with tool to fetch data and create research notes.
    The LLM will decide when to call the fetch_research_data tool.
    
    Args:
        state: Current workflow state
        
    Returns:
        Updated state with messages and research_notes
    """
    print(f"\n[Researcher] Starting research on: {state['topic']}")
    
    # Get prompts from config
    system_prompt = config.researcher_system_prompt
    user_prompt_template = config.researcher_user_prompt
    
    # Format user prompt with topic
    user_message = user_prompt_template.prompt.format(topic=state['topic'])
    
    # Initialize messages if not present
    messages = state.get("messages", [])
    if not messages:
        messages = [
            SystemMessage(content=system_prompt.prompt),
            HumanMessage(content=user_message),
        ]
    
    print(f"[Researcher] Calling LLM with tool access...")
    
    # Create LLM with tools bound
    tools = [fetch_research_data]
    tracer = OpikTracer()
    llm = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.7,
        callbacks=[tracer],
    ).bind_tools(tools)
    
    # Call LLM - it will decide whether to use the tool
    response = llm.invoke(messages)
    messages.append(response)
    
    # Check if LLM wants to use tools
    if response.tool_calls:
        print(f"[Researcher] LLM is calling {len(response.tool_calls)} tool(s)...")
        
        # Execute tool calls
        for tool_call in response.tool_calls:
            tool_name = tool_call["name"]
            tool_args = tool_call["args"]
            print(f"[Researcher] Executing tool: {tool_name} with args: {tool_args}")
            
            # Call the tool
            if tool_name == "fetch_research_data":
                tool_result = fetch_research_data.run(tool_args)
                print(f"[Researcher] Tool returned {len(tool_result)} characters")
                
                # Add tool result to messages
                from langchain_core.messages import ToolMessage
                messages.append(
                    ToolMessage(
                        content=tool_result,
                        tool_call_id=tool_call["id"],
                    )
                )
        
        # Call LLM again with tool results
        print(f"[Researcher] Calling LLM again with tool results...")
        response = llm.invoke(messages)
        messages.append(response)
    
    research_notes = response.content
    print(f"[Researcher] Research notes created: {len(research_notes)} characters")
    
    return {
        **state,
        "messages": messages,
        "research_notes": research_notes,
    }


def writer_agent(state: ResearchState) -> ResearchState:
    """
    Writer agent: Creates final report from research notes and conversation history.
    
    Args:
        state: Current workflow state with research_notes and messages
        
    Returns:
        Updated state with final_report
    """
    print(f"\n[Writer] Creating report on: {state['topic']}")
    
    # Get prompts from config
    system_prompt = config.writer_system_prompt
    user_prompt_template = config.writer_user_prompt
    
    # Extract data from messages (find ToolMessage content)
    data_fetched = ""
    for msg in state.get("messages", []):
        if hasattr(msg, "type") and msg.type == "tool":
            data_fetched = msg.content
            break
    
    # Format user prompt with topic, research_notes, and data
    user_message = user_prompt_template.prompt.format(
        topic=state['topic'],
        research_notes=state['research_notes'],
        data=data_fetched
    )
    
    print(f"[Writer] Writing report with LLM...")
    
    # Create LLM for final report (no tools needed)
    tracer = OpikTracer()
    llm = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.7,
        callbacks=[tracer],
    )
    
    messages = [
        SystemMessage(content=system_prompt.prompt),
        HumanMessage(content=user_message),
    ]
    response = llm.invoke(messages)
    final_report = response.content
    
    print(f"[Writer] Report completed: {len(final_report)} characters")
    
    return {
        **state,
        "final_report": final_report,
    }


# ============================================================================
# Graph Building
# ============================================================================

def build_research_workflow() -> StateGraph:
    """
    Build the research workflow graph.
    
    Returns:
        Compiled StateGraph with Opik tracking
    """
    workflow = StateGraph(ResearchState)
    
    # Register agent nodes
    workflow.add_node("researcher", researcher_agent)
    workflow.add_node("writer", writer_agent)
    
    # Define sequential flow
    workflow.set_entry_point("researcher")
    workflow.add_edge("researcher", "writer")
    workflow.add_edge("writer", END)
    
    return workflow.compile()


# Build the graph
graph = build_research_workflow()
tracer = OpikTracer()


# ============================================================================
# Main Execution Function
# ============================================================================

@opik.track(name="research_workflow", project_name="itamar_agent_app")
def run_research_agent(topic: str) -> str:
    """
    Execute the research workflow for a given topic.
    
    This function is traced by Opik for observability.
    
    Args:
        topic: The research topic
        
    Returns:
        Final report as a string
    """
    print(f"\n{'='*60}")
    print("Starting Research Workflow")
    print(f"Topic: {topic}")
    print(f"{'='*60}\n")
    
    initial_state: ResearchState = {
        "topic": topic,
        "messages": [],
        "research_notes": "",
        "final_report": "",
    }
    
    print("📊 Step 1/2: Researching and analyzing data...")
    print("📝 Step 2/2: Writing final report...")
    
    result = graph.invoke(initial_state, config={"callbacks": [tracer]})
    
    print("\n✅ Research workflow complete!\n")
    
    # Return just the final report for Opik tracking
    return result["final_report"]


# ============================================================================
# Flask API
# ============================================================================

app = Flask(__name__)


@app.route("/health")
def health():
    """Health check endpoint."""
    return {"status": "ok"}


@app.route("/chat", methods=["POST"])
def chat():
    """
    Main chat endpoint for research requests.
    
    Expects JSON: {"message": "topic to research"}
    Returns JSON: {"response": "final report"}
    """
    with experiment_context(request):
        topic = request.json.get("message").get("topic")
        final_report = run_research_agent(topic)
        
    return jsonify({"response": final_report})


# ============================================================================
# Testing
# ============================================================================

if __name__ == "__main__":
    # Test the agent locally
    print("Testing Research Agent...")
    print("\n" + "="*60)
    
    # Test with AI in Education
    final_report = run_research_agent("AI in Education")
    
    print("\n" + "="*60)
    print("FINAL REPORT:")
    print("="*60)
    print(final_report)
    print("\n" + "="*60)
    
    # Optionally start Flask server
    app.run(port=8001)
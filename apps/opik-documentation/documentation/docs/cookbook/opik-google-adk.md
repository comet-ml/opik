---
title: Monitoring Google ADK Agents with Opik
description: Learn how to trace and evaluate Google Agent Development Kit (ADK) agents using Opik for full observability into agent reasoning, tool calls, and LLM responses.
---

# Monitoring Google ADK Agents with Opik

[Google Agent Development Kit (ADK)](https://google.github.io/adk-docs/) is a framework for building multi-agent systems powered by Gemini models. Opik integrates natively with ADK to give you full observability into every step of your agent's reasoning — tool calls, sub-agent invocations, LLM responses, and more.

This guide walks you through setting up Opik tracing for a Google ADK agent from scratch.

---

## Prerequisites

- Python >= 3.9
- A [Comet account](https://www.comet.com/signup?from=llm) (free tier works) or a [self-hosted Opik instance](https://www.comet.com/docs/opik/self-host/overview)
- A Google AI API key from [Google AI Studio](https://aistudio.google.com/apikey)

---

## Step 1 — Install Dependencies

```bash
pip install opik google-adk
```

---

## Step 2 — Configure Opik

Run the interactive setup:

```bash
opik configure
```

This stores your API key and workspace locally. Alternatively, set environment variables:

```bash
export OPIK_API_KEY="your-opik-api-key"
export OPIK_WORKSPACE="your-workspace-name"
export OPIK_PROJECT_NAME="google-adk-agents"
```

---

## Step 3 — Set Your Google AI API Key

```bash
export GOOGLE_API_KEY="your-google-ai-api-key"
```

---

## Step 4 — Build a Simple ADK Agent with Opik Tracing

The Opik integration for Google ADK works via a session callback. Add it when creating your `Runner` and all agent steps are automatically traced.

```python
import opik
from opik.integrations.adk import OpikTracer
from google.adk.agents import Agent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.adk.tools import google_search

# Configure Opik (reads from env or ~/.opik/config)
opik.configure()

# Define your agent
search_agent = Agent(
    name="search_agent",
    model="gemini-2.0-flash",
    instruction="""
        You are a helpful research assistant. When the user asks a question,
        search for accurate and up-to-date information and provide a clear,
        concise answer with sources.
    """,
    tools=[google_search],
)

# Set up session service and Opik tracer
session_service = InMemorySessionService()
opik_tracer = OpikTracer()

# Create the runner with the Opik callback
runner = Runner(
    agent=search_agent,
    app_name="research-assistant",
    session_service=session_service,
    callbacks=[opik_tracer],   # <-- this enables Opik tracing
)

# Run a query
session = session_service.create_session(
    app_name="research-assistant",
    user_id="user-1",
)

response = runner.run(
    user_id="user-1",
    session_id=session.id,
    new_message="What are the most important AI research papers published in 2025?",
)

print(response.text)
```

After running this, open your [Opik dashboard](https://www.comet.com/opik) and navigate to **Projects → google-adk-agents**. You will see a full trace with every agent step, tool call, and LLM response logged automatically.

---

## Step 5 — Build a Multi-Agent Pipeline

ADK supports hierarchical multi-agent systems. Opik traces the full chain including sub-agent calls.

```python
import opik
from opik.integrations.adk import OpikTracer
from google.adk.agents import Agent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.adk.tools import google_search

opik.configure()

# Sub-agent: specialized in financial data
finance_agent = Agent(
    name="finance_agent",
    model="gemini-2.0-flash",
    instruction="""
        You are a financial analyst. Answer questions about stock prices,
        market trends, and economic indicators accurately.
    """,
    tools=[google_search],
)

# Sub-agent: specialized in tech news
tech_agent = Agent(
    name="tech_agent",
    model="gemini-2.0-flash",
    instruction="""
        You are a technology journalist. Answer questions about AI,
        software, hardware, and tech company news.
    """,
    tools=[google_search],
)

# Orchestrator agent that delegates to sub-agents
orchestrator = Agent(
    name="orchestrator",
    model="gemini-2.0-flash",
    instruction="""
        You are a research orchestrator. For financial questions, delegate
        to the finance_agent. For technology questions, delegate to the
        tech_agent. Combine their responses into a coherent answer.
    """,
    sub_agents=[finance_agent, tech_agent],
)

session_service = InMemorySessionService()
opik_tracer = OpikTracer()

runner = Runner(
    agent=orchestrator,
    app_name="multi-agent-research",
    session_service=session_service,
    callbacks=[opik_tracer],
)

session = session_service.create_session(
    app_name="multi-agent-research",
    user_id="user-1",
)

response = runner.run(
    user_id="user-1",
    session_id=session.id,
    new_message="How did AI chip stocks perform this week and what new AI models were released?",
)

print(response.text)
```

In the Opik UI, the trace will show the orchestrator's reasoning, which sub-agent was invoked, the tool calls made, and each model response — all in a single hierarchical view.

---

## Step 6 — Add Custom Evaluation Scores

Beyond automatic tracing, you can attach evaluation scores to traces. This is useful for measuring answer quality in production.

```python
import opik
from opik import track
from opik.evaluation.metrics import Hallucination, AnswerRelevance
from opik.integrations.adk import OpikTracer
from google.adk.agents import Agent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService

opik.configure()

# Initialize evaluation metrics
hallucination_metric = Hallucination()
relevance_metric = AnswerRelevance()

session_service = InMemorySessionService()
opik_tracer = OpikTracer()

agent = Agent(
    name="qa_agent",
    model="gemini-2.0-flash",
    instruction="Answer questions accurately and concisely.",
)

runner = Runner(
    agent=agent,
    app_name="evaluated-qa",
    session_service=session_service,
    callbacks=[opik_tracer],
)

def run_and_evaluate(question: str) -> str:
    session = session_service.create_session(
        app_name="evaluated-qa",
        user_id="user-1",
    )

    response = runner.run(
        user_id="user-1",
        session_id=session.id,
        new_message=question,
    )

    answer = response.text

    # Score the response and log back to Opik
    trace = opik_tracer.last_trace
    if trace:
        hallucination_score = hallucination_metric.score(
            input=question,
            output=answer,
        )
        relevance_score = relevance_metric.score(
            input=question,
            output=answer,
        )

        trace.log_feedback_score(
            name="hallucination",
            value=hallucination_score.value,
            reason=hallucination_score.reason,
        )
        trace.log_feedback_score(
            name="answer_relevance",
            value=relevance_score.value,
        )

    return answer

# Run with evaluation
answer = run_and_evaluate(
    "What is the current inflation rate in the United States?"
)
print(answer)
```

---

## What Gets Traced Automatically

When you add `OpikTracer` as a callback, Opik automatically captures:

- **Agent invocations** — which agent ran, its instruction, and model used
- **Tool calls** — tool name, input arguments, and returned output
- **Sub-agent delegations** — the full chain of orchestrator → sub-agent calls
- **LLM requests and responses** — prompt, completion, token counts, and latency
- **Session metadata** — user ID, session ID, and app name

---

## Viewing Traces in the Dashboard

After running your agent, open your Opik project:

1. Go to [https://www.comet.com/opik](https://www.comet.com/opik)
2. Click your project (`google-adk-agents`)
3. Select any trace to see the full waterfall view
4. Click individual spans to inspect tool inputs/outputs and LLM responses

For production monitoring, use **Online Evaluation Rules** to automatically score every incoming trace with LLM-as-a-judge metrics.

---

## Troubleshooting

**No traces appearing in the dashboard**

- Confirm `opik.configure()` ran successfully — check `~/.opik/config` for stored credentials.
- Verify the `callbacks=[opik_tracer]` parameter is passed to `Runner`.
- Check that your `OPIK_PROJECT_NAME` environment variable matches what you see in the dashboard.

**Google API authentication errors**

- Ensure `GOOGLE_API_KEY` is set correctly.
- Verify the API key has access to the Gemini models you're using.

**Missing tool call spans**

- Tool calls are only traced if the tool is invoked during the run. Try a question that requires searching.

---

## Related Resources

- [Opik documentation](https://www.comet.com/docs/opik/)
- [Google ADK documentation](https://google.github.io/adk-docs/)
- [Opik Python SDK reference](https://www.comet.com/docs/opik/reference/python-sdk-reference/)
- [Opik bounty program](https://www.comet.com/docs/opik/contributing/developer-programs/bounties)
- [Other Opik cookbooks](https://github.com/comet-ml/opik/tree/main/apps/opik-documentation/documentation/docs/cookbook)

import os

from ._agent import get_agent

# Required for ADK WEB, not working at the moment as ADK Web does not support passing the API key to the agent nor injecting trace id and project name in the state
root_agent = get_agent(user_opik_api_key=os.environ.get("OPIK_API_KEY"))

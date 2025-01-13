import React from "react";
import IntegrationTemplate from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";

const CODE_TITLE =
  "You can use the `track_openai` wrapper to log all OpenAI calls to the Opik platform";

const CODE = `from opik.integrations.openai import track_openai
from openai import OpenAI

os.environ["OPIK_PROJECT_NAME"] = "openai-integration-demo"
client = OpenAI()

openai_client = track_openai(client)

prompt = """
Write a short two sentence story about Opik.
"""

completion = openai_client.chat.completions.create(
  model="gpt-3.5-turbo",
  messages=[
    {"role": "user", "content": prompt}
  ]
)

print(completion.choices[0].message.content)`;

const OpenAI: React.FC<FrameworkIntegrationComponentProps> = ({ apiKey }) => {
  return (
    <IntegrationTemplate apiKey={apiKey} codeTitle={CODE_TITLE} code={CODE} />
  );
};

export default OpenAI;

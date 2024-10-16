import React from "react";
import { IntegrationComponentProps } from "@/components/pages/QuickstartPage/integrations/types";
import openAILogoUrl from "/images/integrations/openai.png";
import IntegrationTemplate from "@/components/pages/QuickstartPage/integrations/IntegrationTemplate";

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

const OpenAI: React.FC<IntegrationComponentProps> = ({ apiKey }) => {
  return (
    <IntegrationTemplate
      apiKey={apiKey}
      integration="OpenAI"
      url={openAILogoUrl}
      codeTitle={CODE_TITLE}
      code={CODE}
    />
  );
};

export default OpenAI;

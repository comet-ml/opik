import React from "react";
import IntegrationTemplate, {
  OPIK_API_KEY_TEMPLATE,
} from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";

const CODE_TITLE =
  "You can use the `track_openai` wrapper to log all OpenAI calls to the Opik platform";

const CODE = `import getpass
import os

from openai import OpenAI
from opik.integrations.openai import track_openai
${OPIK_API_KEY_TEMPLATE}
if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

openai_client = OpenAI()
openai_client = track_openai(openai_client)

prompt = "Hello, world!"

response = openai_client.chat.completions.create(
    model="gpt-3.5-turbo",
    messages=[{"role": "user", "content": prompt}],
    temperature=0.7,
    max_tokens=100,
    top_p=1,
    frequency_penalty=0,
    presence_penalty=0,
)

print(response.choices[0].message.content)`;

const OpenAI: React.FC<FrameworkIntegrationComponentProps> = ({ apiKey }) => {
  return (
    <IntegrationTemplate apiKey={apiKey} codeTitle={CODE_TITLE} code={CODE} />
  );
};

export default OpenAI;

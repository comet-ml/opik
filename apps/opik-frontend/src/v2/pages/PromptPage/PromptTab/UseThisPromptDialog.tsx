import React from "react";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

type UseThisPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  promptName: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  versionLabel?: string;
  versionCommit?: string;
};

const getCreatingPrompt = (promptName: string) => `import opik

client = opik.Opik()

# Create a new prompt (or a new version if it already exists)
prompt = client.create_prompt(
  name="${promptName}",
  prompt="Hello, {{name}}! Welcome to {{location}}. How can I assist you today?",
  metadata={"temperature": 0.4},
)

# Format the prompt with the given parameters
formatted_prompt = prompt.format(name="Alice", location="Wonderland")
print(formatted_prompt)
`;

const getGettingPrompt = (promptName: string, commit?: string) =>
  commit
    ? `import opik

client = opik.Opik()

# Get a specific version of a prompt by commit
prompt = client.get_prompt(name="${promptName}", commit="${commit}")

# Read metadata from this version of the prompt
print(prompt.metadata)

# Format the prompt with the given parameters
formatted_prompt = prompt.format({"name": "Alice", "location": "Wonderland"})
print(formatted_prompt)
`
    : `import opik

client = opik.Opik()

# Get the most recent version of a prompt
prompt = client.get_prompt(name="${promptName}")

# Read metadata from the most recent version of a prompt
print(prompt.metadata)

# Format the prompt with the given parameters
formatted_prompt = prompt.format(name="Alice", location="Wonderland")
print(formatted_prompt)
`;

const getCreatingChatPrompt = (promptName: string) => `import opik

client = opik.Opik()

# Create a new chat prompt (or a new version if it already exists)
chat_prompt = client.create_chat_prompt(
  name="${promptName}",
  messages=[
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "Hello, {{name}}! How can you help me with {{topic}}?"},
  ],
  metadata={"temperature": 0.7},
)

# Format the chat prompt with the given parameters
formatted_messages = chat_prompt.format(variables={"name": "Alice", "topic": "Python programming"})
print(formatted_messages)
`;

const getGettingChatPrompt = (promptName: string, commit?: string) =>
  commit
    ? `import opik

client = opik.Opik()

# Get a specific version of a chat prompt by commit
chat_prompt = client.get_chat_prompt(name="${promptName}", commit="${commit}")

# Read metadata from this version of the chat prompt
print(chat_prompt.metadata)

# Format the chat prompt with the given parameters
formatted_messages = chat_prompt.format({"name": "Alice", "topic": "Python programming"})
print(formatted_messages)
`
    : `import opik

client = opik.Opik()

# Get the most recent version of a chat prompt
chat_prompt = client.get_chat_prompt(name="${promptName}")

# Read metadata from the most recent version of a chat prompt
print(chat_prompt.metadata)

# Format the chat prompt with the given parameters
formatted_messages = chat_prompt.format(variables={"name": "Alice", "topic": "Python programming"})
print(formatted_messages)
`;

const UseThisPromptDialog: React.FunctionComponent<
  UseThisPromptDialogProps
> = ({
  open,
  setOpen,
  promptName,
  templateStructure,
  versionLabel,
  versionCommit,
}) => {
  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  const promptKind = isChatPrompt ? "chat prompt" : "prompt";
  const versionSuffix = versionLabel ? ` (${versionLabel})` : "";

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className="w-[90vw]"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>
            Use this {promptKind}
            {versionSuffix}
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[EXPLAINER_ID.how_do_i_use_this_prompt]}
          />
          <div className="flex flex-col gap-2">
            <div className="comet-body-accented mt-4">
              Creating a {promptKind}
            </div>
            <CodeHighlighter
              data={
                isChatPrompt
                  ? getCreatingChatPrompt(promptName)
                  : getCreatingPrompt(promptName)
              }
            />
            <div className="comet-body-accented mt-4">
              {versionCommit
                ? `Getting this ${promptKind}${versionSuffix}`
                : `Getting a ${promptKind}`}
            </div>
            <CodeHighlighter
              data={
                isChatPrompt
                  ? getGettingChatPrompt(promptName, versionCommit)
                  : getGettingPrompt(promptName, versionCommit)
              }
            />
          </div>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default UseThisPromptDialog;

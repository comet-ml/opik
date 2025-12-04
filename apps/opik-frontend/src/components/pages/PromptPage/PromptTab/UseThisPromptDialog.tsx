import React from "react";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

type UseThisPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  promptName: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
};

const getCreatingPrompt = (promptName: string) => `import opik

# Create a new Prompt instance
prompt = opik.Prompt(
  name="${promptName}",
  prompt="Hello, {{name}}! Welcome to {{location}}. How can I assist you today?",
  metadata={"temperature": 0.4}
)

# Format the prompt with the given parameters
formatted_prompt = prompt.format({"name": "Alice", "location": "Wonderland"})
print(formatted_prompt)
`;

const getGettingPrompt = (promptName: string) => `import opik

client = opik.Opik()

# Get the most recent version of a prompt
prompt = client.get_prompt(name="${promptName}")

# Read metadata from the most recent version of a prompt
print(prompt.metadata)

# Format the prompt with the given parameters
formatted_prompt = prompt.format({"name": "Alice", "location": "Wonderland"})
print(formatted_prompt)
`;

const getCreatingChatPrompt = (promptName: string) => `import opik

# Create a new ChatPrompt instance
chat_prompt = opik.ChatPrompt(
  name="${promptName}",
  messages=[
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "Hello, {{name}}! How can you help me with {{topic}}?"}
  ],
  metadata={"temperature": 0.7}
)

# Format the chat prompt with the given parameters
formatted_messages = chat_prompt.format({"name": "Alice", "topic": "Python programming"})
print(formatted_messages)
`;

const getGettingChatPrompt = (promptName: string) => `import opik

client = opik.Opik()

# Get the most recent version of a chat prompt
chat_prompt = client.get_chat_prompt(name="${promptName}")

# Read metadata from the most recent version of a chat prompt
print(chat_prompt.metadata)

# Format the chat prompt with the given parameters
formatted_messages = chat_prompt.format({"name": "Alice", "topic": "Python programming"})
print(formatted_messages)
`;

const UseThisPromptDialog: React.FunctionComponent<
  UseThisPromptDialogProps
> = ({ open, setOpen, promptName, templateStructure }) => {
  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className="w-[90vw]"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>
            Use this {isChatPrompt ? "chat " : ""}prompt
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[EXPLAINER_ID.how_do_i_use_this_prompt]}
          />
          <div className="flex flex-col gap-2">
            <div className="comet-body-accented mt-4">
              Creating a {isChatPrompt ? "chat " : ""}prompt
            </div>
            <CodeHighlighter
              data={
                isChatPrompt
                  ? getCreatingChatPrompt(promptName)
                  : getCreatingPrompt(promptName)
              }
            />
            <div className="comet-body-accented mt-4">
              Getting a {isChatPrompt ? "chat " : ""}prompt
            </div>
            <CodeHighlighter
              data={
                isChatPrompt
                  ? getGettingChatPrompt(promptName)
                  : getGettingPrompt(promptName)
              }
            />
          </div>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default UseThisPromptDialog;

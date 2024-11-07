import React from "react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";

type UseThisPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  promptName: string;
};

const getCreatingPrompt = (promptName: string) => `import opik

# Create a new Prompt instance
prompt = opik.Prompt(
  name="${promptName}",
  prompt="Hello, {name}! Welcome to {location}. How can I assist you today?"
)

# Format the prompt with the given parameters
formatted_prompt = prompt.format(name="Alice", location="Wonderland")
print(formatted_prompt)
`;

const getGettingPrompt = (promptName: string) => `import opik

client = opik.Opik()

# Get the most recent version of a prompt
prompt = client.get_prompt(name="${promptName}")

# Format the prompt with the given parameters
formatted_prompt = prompt.format(name="Alice", location="Wonderland")
print(formatted_prompt)
`;

const UseThisPromptDialog: React.FunctionComponent<
  UseThisPromptDialogProps
> = ({ open, setOpen, promptName }) => {
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className="w-[90vw]"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>Use this prompt</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-2">
          <div className="comet-body-accented mt-4">Creating a prompt</div>
          <CodeHighlighter data={getCreatingPrompt(promptName)} />
          <div className="comet-body-accented mt-4">Getting a prompt</div>
          <CodeHighlighter data={getGettingPrompt(promptName)} />
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default UseThisPromptDialog;

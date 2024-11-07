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
};

const CREATING_PROMPT = `import opik

# Create a new Prompt instance
prompt = opik.Prompt(
  name="greeting_prompt",
  prompt="Hello, {name}! Welcome to {location}. How can I assist you today?"
)

# Format the prompt with the given parameters
formatted_prompt = prompt.format(name="Alice", location="Wonderland")
print(formatted_prompt)
`;

const GETTING_PROMPT = `import opik

client = opik.Opik()

# Get the most recent version of a prompt
prompt = client.get_prompt(name="greeting_prompt")

# Format the prompt with the given parameters
formatted_prompt = prompt.format(name="Alice", location="Wonderland")
print(formatted_prompt)
`;

const UseThisPromptDialog: React.FunctionComponent<
  UseThisPromptDialogProps
> = ({ open, setOpen }) => {
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
          <CodeHighlighter data={CREATING_PROMPT} />
          <div className="comet-body-accented mt-4">Getting a prompt</div>
          <CodeHighlighter data={GETTING_PROMPT} />
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default UseThisPromptDialog;

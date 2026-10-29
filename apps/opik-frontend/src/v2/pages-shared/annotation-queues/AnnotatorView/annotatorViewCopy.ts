import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";

export const getAnnotatorViewDescription = (editor: AnnotatorViewEditor) => {
  if (editor.hasView) {
    return (
      editor.draft.summary || "Annotators see a custom view written with Ollie."
    );
  }
  return editor.scope === "thread"
    ? "Annotators see the thread as a conversation of chat messages."
    : "Annotators see the trace input, output and metadata as code blocks.";
};

export const getAnnotatorViewNote = (editor: AnnotatorViewEditor) => {
  if (editor.isSamplesPending) return undefined;
  if (!editor.samples.length) {
    return editor.scope === "thread"
      ? "No threads in this project yet. Log traces with a thread ID."
      : "No traces in this project yet. Log traces to customize the view.";
  }
  if (!editor.fromProject) return undefined;
  return editor.hasQueue
    ? `No items in this queue yet. Previewing the latest ${editor.scope}s from the project.`
    : `Previewing the latest ${editor.scope}s from the project.`;
};

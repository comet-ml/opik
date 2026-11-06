import React, { forwardRef, useCallback } from "react";
import { flushSync } from "react-dom";
// eslint-disable-next-line no-restricted-imports
import ReactCodeMirror, {
  ReactCodeMirrorProps,
  ReactCodeMirrorRef,
  ViewUpdate,
} from "@uiw/react-codemirror";

/**
 * Drop-in for `@uiw/react-codemirror` that commits each edit before the next keystroke.
 *
 * The wrapper syncs `value` into the document from an effect. React 19 can run a typing update's
 * effects only after the next keystroke, and the sync would then replace the document with the older
 * text, losing what was just typed. Committing the edit synchronously runs that effect at once.
 */
const CodeMirrorEditor = forwardRef<ReactCodeMirrorRef, ReactCodeMirrorProps>(
  ({ onChange, ...props }, ref) => {
    const handleChange = useCallback(
      (value: string, update: ViewUpdate) =>
        flushSync(() => onChange?.(value, update)),
      [onChange],
    );

    return (
      <ReactCodeMirror
        ref={ref}
        onChange={onChange ? handleChange : undefined}
        {...props}
      />
    );
  },
);

CodeMirrorEditor.displayName = "CodeMirrorEditor";

export default CodeMirrorEditor;

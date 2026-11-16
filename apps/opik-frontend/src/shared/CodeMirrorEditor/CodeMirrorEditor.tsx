import React, { forwardRef, useCallback } from "react";
import { flushSync } from "react-dom";
// eslint-disable-next-line no-restricted-imports
import ReactCodeMirror, {
  ReactCodeMirrorProps,
  ReactCodeMirrorRef,
  Transaction,
  ViewUpdate,
} from "@uiw/react-codemirror";

const isUserUpdate = (update: ViewUpdate) =>
  update.transactions.some(
    (tr) => tr.annotation(Transaction.userEvent) !== undefined,
  );

/**
 * Drop-in for `@uiw/react-codemirror` that commits each edit before the next keystroke.
 *
 * The wrapper syncs `value`, `onUpdate` and `extensions` into the editor from effects. React 19 can run
 * a typing update's effects only after the next keystroke, which then meets older text or handlers
 * built from older state, so typed characters get lost or handled by the wrong state. Committing
 * user edits synchronously runs those effects at once. Updates the editor makes itself, such as
 * reconfiguring from inside those effects, keep the normal path.
 */
const CodeMirrorEditor = forwardRef<ReactCodeMirrorRef, ReactCodeMirrorProps>(
  ({ onChange, onUpdate, ...props }, ref) => {
    const handleChange = useCallback(
      (value: string, update: ViewUpdate) =>
        flushSync(() => onChange?.(value, update)),
      [onChange],
    );

    const handleUpdate = useCallback(
      (update: ViewUpdate) => {
        if (isUserUpdate(update)) flushSync(() => onUpdate?.(update));
        else onUpdate?.(update);
      },
      [onUpdate],
    );

    return (
      <ReactCodeMirror
        ref={ref}
        onChange={onChange ? handleChange : undefined}
        onUpdate={onUpdate ? handleUpdate : undefined}
        {...props}
      />
    );
  },
);

CodeMirrorEditor.displayName = "CodeMirrorEditor";

export default CodeMirrorEditor;

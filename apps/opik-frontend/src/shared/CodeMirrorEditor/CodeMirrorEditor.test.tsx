import { describe, it, expect, afterEach } from "vitest";
import { act, render } from "@testing-library/react";
import React, { createRef, useEffect, useState } from "react";
import { ReactCodeMirrorRef } from "@uiw/react-codemirror";

import CodeMirrorEditor from "./CodeMirrorEditor";

const actEnvironment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };

afterEach(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
});

describe("CodeMirrorEditor", () => {
  it("commits an edit and its effects before the next keystroke can arrive", async () => {
    const ref = createRef<ReactCodeMirrorRef>();
    const committed: string[] = [];

    const Parent = () => {
      const [value, setValue] = useState("a");
      useEffect(() => {
        committed.push(value);
      }, [value]);
      return <CodeMirrorEditor ref={ref} value={value} onChange={setValue} />;
    };

    render(<Parent />);
    await act(async () => {});
    const view = ref.current!.view!;

    // Outside act, as in the browser: nothing flushes React work for us.
    actEnvironment.IS_REACT_ACT_ENVIRONMENT = false;
    view.dispatch({ changes: { from: 1, insert: "b" } });

    expect(committed.at(-1)).toBe("ab");
    view.dispatch({ changes: { from: 2, insert: "c" } });

    expect(committed.at(-1)).toBe("abc");
    expect(view.state.doc.toString()).toBe("abc");
  });

  it("still applies a value set from outside the editor", async () => {
    const ref = createRef<ReactCodeMirrorRef>();
    const { rerender } = render(
      <CodeMirrorEditor ref={ref} value="draft" onChange={() => {}} />,
    );
    await act(async () => {});

    rerender(<CodeMirrorEditor ref={ref} value="reset" onChange={() => {}} />);
    await act(async () => {});

    expect(ref.current!.view!.state.doc.toString()).toBe("reset");
  });
});

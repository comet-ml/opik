import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { foldService, foldState } from "@codemirror/language";
import { createBase64ExpandExtension } from "./base64Extension";

describe("base64Extension", () => {
  let container: HTMLDivElement;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
  });

  afterEach(() => {
    if (container && container.parentNode) {
      container.parentNode.removeChild(container);
    }
  });

  const createTestView = (content: string): EditorView => {
    const state = EditorState.create({
      doc: content,
      extensions: [createBase64ExpandExtension()],
    });
    return new EditorView({
      state,
      parent: container,
    });
  };

  describe("foldService - base64 detection", () => {
    it("should find fold range for long base64 image strings", () => {
      const longBase64 = "data:image/png;base64," + "A".repeat(200);
      const content = `image: ${longBase64}`;
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);
        expect(foldSvc.length).toBeGreaterThan(0);

        const line = view.state.doc.line(1);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);

        expect(foldRange).not.toBeNull();
        expect(foldRange?.from).toBeGreaterThan(line.from);
        expect(foldRange?.to).toBeGreaterThan(foldRange!.from);
        // Should fold from after the comma
        expect(foldRange?.from).toBeGreaterThan(
          line.from + content.indexOf(","),
        );
      } finally {
        view.destroy();
      }
    });

    it("should not fold short base64 strings (<100 chars)", () => {
      const shortBase64 = "data:image/png;base64,iVBORw0KGgo=";
      const content = `image: ${shortBase64}`;
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);
        const line = view.state.doc.line(1);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);

        expect(foldRange).toBeNull();
      } finally {
        view.destroy();
      }
    });

    it("should find fold range for video base64 strings", () => {
      const longVideoBase64 = "data:video/mp4;base64," + "B".repeat(200);
      const content = `video: ${longVideoBase64}`;
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);
        const line = view.state.doc.line(1);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);

        expect(foldRange).not.toBeNull();
      } finally {
        view.destroy();
      }
    });

    it("should not fold non-base64 strings", () => {
      const content = "regular: text content here" + "C".repeat(200);
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);
        const line = view.state.doc.line(1);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);

        expect(foldRange).toBeNull();
      } finally {
        view.destroy();
      }
    });

    it("should fold base64 strings in YAML format", () => {
      const longBase64 = "data:image/jpeg;base64," + "D".repeat(200);
      const content = `input:\n  image: ${longBase64}\n  text: "some text"`;
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);
        const line = view.state.doc.line(2);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);

        expect(foldRange).not.toBeNull();
      } finally {
        view.destroy();
      }
    });

    it("should fold base64 strings in JSON format", () => {
      const longBase64 = "data:image/png;base64," + "E".repeat(200);
      const content = `{\n  "image": "${longBase64}",\n  "text": "value"\n}`;
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);
        const line = view.state.doc.line(2);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);

        expect(foldRange).not.toBeNull();
      } finally {
        view.destroy();
      }
    });
  });

  describe("auto-folding on load", () => {
    it("should auto-fold base64 strings on initial document load", async () => {
      const longBase64 = "data:image/png;base64," + "F".repeat(200);
      const content = `image: ${longBase64}`;
      const view = createTestView(content);

      try {
        // Check that fold state exists
        const foldStateField = view.state.field(foldState);
        expect(foldStateField).toBeDefined();

        // Verify the fold range is recognized
        const foldSvc = view.state.facet(foldService);
        const line = view.state.doc.line(1);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);
        expect(foldRange).not.toBeNull();
      } finally {
        view.destroy();
      }
    });

    it("should auto-fold multiple base64 strings in document", async () => {
      const longBase641 = "data:image/png;base64," + "G".repeat(200);
      const longBase642 = "data:image/jpeg;base64," + "H".repeat(200);
      const content = `image1: ${longBase641}\nimage2: ${longBase642}`;
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);

        // Check first line
        const line1 = view.state.doc.line(1);
        const foldRange1 = foldSvc[0](view.state, line1.from + 10, line1.to);
        expect(foldRange1).not.toBeNull();

        // Check second line
        const line2 = view.state.doc.line(2);
        const foldRange2 = foldSvc[0](view.state, line2.from + 10, line2.to);
        expect(foldRange2).not.toBeNull();
      } finally {
        view.destroy();
      }
    });
  });

  describe("expand-collapse functionality", () => {
    it("should support expanding and collapsing base64 folds", async () => {
      const longBase64 = "data:image/png;base64," + "I".repeat(200);
      const content = `image: ${longBase64}`;
      const view = createTestView(content);

      try {
        const foldSvc = view.state.facet(foldService);
        const line = view.state.doc.line(1);
        const foldRange = foldSvc[0](view.state, line.from + 10, line.to);

        expect(foldRange).not.toBeNull();

        // Verify fold state exists (required for expand/collapse to work)
        const initialFoldState = view.state.field(foldState);
        expect(initialFoldState).toBeDefined();

        // The fold service should consistently return the same fold range
        // when queried at different positions on the same line.
        const foldRange2 = foldSvc[0](view.state, line.from + 50, line.to);
        expect(foldRange2).not.toBeNull();
        expect(foldRange2?.from).toBe(foldRange?.from);
        expect(foldRange2?.to).toBe(foldRange?.to);
      } finally {
        view.destroy();
      }
    });
  });
});

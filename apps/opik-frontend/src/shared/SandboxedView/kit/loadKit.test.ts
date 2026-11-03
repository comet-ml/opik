import { afterEach, describe, expect, it, vi } from "vitest";
import { loadKit } from "./loadKit";

type KitApi = {
  stripHtml: (value: string) => string;
  emailFrame: (html: string) => HTMLIFrameElement;
};

const runKit = () => {
  new Function(loadKit(1, ["review"]).js)();
  return (window as unknown as { kit: KitApi }).kit;
};

describe("loadKit", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    delete (window as { kit?: unknown }).kit;
  });

  it("gives the core and pack helpers access to the HTML policy", () => {
    const kit = runKit();

    expect(kit.stripHtml("<p>Hello <b>there</b></p>")).toBe("Hello there");
    expect(kit.emailFrame("<p>Hi</p>").getAttribute("srcdoc")).toBe(
      "<p>Hi</p>",
    );
  });

  it("creates the kit policy when Trusted Types are available", () => {
    const createHTML = vi.fn((value: string) => value);
    const createPolicy = vi.fn(() => ({ createHTML }));
    vi.stubGlobal("trustedTypes", { createPolicy });

    const kit = runKit();
    kit.stripHtml("<p>Hi</p>");

    expect(createPolicy).toHaveBeenCalledWith("kit", expect.any(Object));
    expect(createHTML).toHaveBeenCalledWith("<p>Hi</p>");
  });
});

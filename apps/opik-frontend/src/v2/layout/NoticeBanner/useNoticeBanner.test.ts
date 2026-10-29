import { describe, it, expect } from "vitest";

import { parsePayload } from "./useNoticeBanner";

describe("parsePayload", () => {
  it("returns null without a string message", () => {
    expect(parsePayload(undefined)).toBeNull();
    expect(parsePayload("text")).toBeNull();
    expect(parsePayload({ message: "" })).toBeNull();
    expect(parsePayload({ message: { text: "x" } })).toBeNull();
  });

  it("drops fields that are not strings", () => {
    const notice = parsePayload({
      message: "Hello",
      cta_text: 42,
      icon: { name: "x" },
      background_color: ["red"],
    });

    expect(notice).toMatchObject({ message: "Hello", dismissible: true });
    expect(notice?.ctaText).toBeUndefined();
    expect(notice?.icon).toBeUndefined();
    expect(notice?.backgroundColor).toBeUndefined();
  });

  it("keeps only https, http and mailto URLs", () => {
    const urlOf = (url: string) =>
      parsePayload({ message: "Hello", cta_url: url })?.ctaUrl;

    expect(urlOf("https://www.comet.com")).toBe("https://www.comet.com");
    expect(urlOf("http://example.com")).toBe("http://example.com");
    expect(urlOf("mailto:product@comet.com")).toBe("mailto:product@comet.com");
    expect(urlOf("javascript:alert(1)")).toBeUndefined();
    expect(urlOf(" JavaScript:alert(1)")).toBeUndefined();
    expect(urlOf("data:text/html,<script>alert(1)</script>")).toBeUndefined();
  });

  it("applies the same URL check to icon_url", () => {
    expect(
      parsePayload({ message: "Hello", icon_url: "javascript:alert(1)" })
        ?.iconUrl,
    ).toBeUndefined();
  });

  it("is dismissible unless dismiss is false", () => {
    expect(parsePayload({ message: "Hello" })?.dismissible).toBe(true);
    expect(
      parsePayload({ message: "Hello", dismiss: false })?.dismissible,
    ).toBe(false);
  });
});

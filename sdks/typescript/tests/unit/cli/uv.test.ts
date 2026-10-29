import { describe, it, expect } from "vitest";
import path from "node:path";
import { uvBinaryName, uvCandidatePaths } from "@/cli/uv";

const HOME = path.join(path.sep, "home", "someone");

describe("uvBinaryName", () => {
  it("asks for the .exe on Windows", () => {
    expect(uvBinaryName("win32")).toBe("uv.exe");
    expect(uvBinaryName("darwin")).toBe("uv");
  });
});

describe("uvCandidatePaths", () => {
  it("looks where uv's installer puts the binary", () => {
    const candidates = uvCandidatePaths({}, "linux", HOME);

    expect(candidates).toEqual([
      path.join(HOME, ".local", "bin", "uv"),
      path.join(HOME, ".cargo", "bin", "uv"),
    ]);
  });

  it("prefers the directories the environment names", () => {
    const candidates = uvCandidatePaths(
      { XDG_BIN_HOME: "/opt/bin", CARGO_HOME: "/opt/cargo" },
      "linux",
      HOME,
    );

    expect(candidates.slice(0, 2)).toEqual([
      path.join("/opt/bin", "uv"),
      path.join("/opt/cargo", "bin", "uv"),
    ]);
  });

  it("ignores a blank root rather than joining it into a relative path", () => {
    // `CARGO_HOME=""` would otherwise name `bin/uv`, and probing that runs
    // whatever the working directory happens to hold.
    const candidates = uvCandidatePaths(
      { CARGO_HOME: "", XDG_BIN_HOME: "   " },
      "linux",
      HOME,
    );

    expect(candidates).toEqual([
      path.join(HOME, ".local", "bin", "uv"),
      path.join(HOME, ".cargo", "bin", "uv"),
    ]);
  });

  it("ignores a relative root", () => {
    const candidates = uvCandidatePaths(
      { XDG_BIN_HOME: "relative/bin" },
      "linux",
      HOME,
    );

    expect(candidates).not.toContain(path.join("relative", "bin", "uv"));
  });

  it("does not repeat a directory the environment points at twice", () => {
    const candidates = uvCandidatePaths(
      { XDG_BIN_HOME: path.join(HOME, ".local", "bin") },
      "linux",
      HOME,
    );

    expect(candidates).toEqual([
      path.join(HOME, ".local", "bin", "uv"),
      path.join(HOME, ".cargo", "bin", "uv"),
    ]);
  });
});

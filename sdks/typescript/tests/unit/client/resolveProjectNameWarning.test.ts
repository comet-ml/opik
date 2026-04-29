import { Opik } from "opik";
import { logger } from "@/utils/logger";
import { DEFAULT_CONFIG } from "@/config/Config";
import { resetDefaultProjectWarning } from "@/client/Client";

describe("resolveProjectName default project warning", () => {
  let loggerWarnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    resetDefaultProjectWarning();
    loggerWarnSpy = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("warns once when falling back to Default Project", () => {
    const client = new Opik();

    client.resolveProjectName(undefined);

    expect(loggerWarnSpy).toHaveBeenCalledTimes(1);
    expect(loggerWarnSpy).toHaveBeenCalledWith(
      expect.stringContaining("No project name configured")
    );
  });

  it("does not warn a second time", () => {
    const client = new Opik();

    client.resolveProjectName(undefined);
    loggerWarnSpy.mockClear();

    client.resolveProjectName(undefined);

    expect(loggerWarnSpy).not.toHaveBeenCalled();
  });

  it("does not warn when projectName is explicitly provided", () => {
    const client = new Opik();

    client.resolveProjectName("My Project");

    expect(loggerWarnSpy).not.toHaveBeenCalled();
  });

  it("does not warn when explicit value equals Default Project", () => {
    const client = new Opik();

    client.resolveProjectName(DEFAULT_CONFIG.projectName);

    expect(loggerWarnSpy).not.toHaveBeenCalled();
  });

  it("does not warn when client has a non-default projectName", () => {
    const client = new Opik({ projectName: "Custom Project" });

    client.resolveProjectName(undefined);

    expect(loggerWarnSpy).not.toHaveBeenCalled();
  });
});

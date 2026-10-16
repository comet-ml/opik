import os from "node:os";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { CLIENT_ID_HEADER, clientIdentifier, userName } from "@/utils/clientId";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("userName", () => {
  it("prefers the environment, in the order Python's getpass uses", () => {
    expect(userName({ USER: "bob", LOGNAME: "alice" })).toBe("alice");
  });

  it("falls back to 'unknown' like the Python SDK when there is no passwd entry", () => {
    vi.spyOn(os, "userInfo").mockImplementation(() => {
      throw new Error("no passwd entry");
    });

    expect(userName({})).toBe("unknown");
  });
});

describe("OpikApiClientTemp client id header", () => {
  const sentHeaders = async (client: OpikApiClientTemp) => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response("{}", { status: 200 }));
    await client.projects.findProjects().catch(() => undefined);
    return new Headers(fetchSpy.mock.calls[0][1]?.headers);
  };

  it("sends the client identifier on every request", async () => {
    const headers = await sentHeaders(
      new OpikApiClientTemp({ environment: "http://localhost/api" }),
    );

    expect(headers.get(CLIENT_ID_HEADER)).toBe(clientIdentifier());
  });

  it("lets caller-supplied headers override it", async () => {
    const headers = await sentHeaders(
      new OpikApiClientTemp({
        environment: "http://localhost/api",
        requestOptions: { headers: { [CLIENT_ID_HEADER]: "custom" } },
      }),
    );

    expect(headers.get(CLIENT_ID_HEADER)).toBe("custom");
  });
});

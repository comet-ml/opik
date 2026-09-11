import {
  installPrefixedOutput,
  uninstallPrefixedOutput,
  getAndClearJobLogs,
} from "@/runner/prefixedOutput";
import { runWithJobContext } from "@/runner/context";

describe("prefixedOutput", () => {
  afterEach(() => {
    uninstallPrefixedOutput();
  });

  describe("installPrefixedOutput / uninstallPrefixedOutput", () => {
    it("installs and uninstalls without errors", () => {
      expect(() => installPrefixedOutput()).not.toThrow();
      expect(() => uninstallPrefixedOutput()).not.toThrow();
    });

    it("is idempotent on install", () => {
      installPrefixedOutput();
      const firstWrite = process.stdout.write;
      installPrefixedOutput();
      expect(process.stdout.write).toBe(firstWrite);
      uninstallPrefixedOutput();
    });

    it("restores original write behavior on uninstall", () => {
      installPrefixedOutput();
      uninstallPrefixedOutput();

      // After uninstall, writing outside a job context should not capture anything
      process.stdout.write("after-uninstall\n");
      const logs = getAndClearJobLogs("any");
      expect(logs).toEqual([]);
    });
  });

  describe("log capture during job context", () => {
    it("captures stdout writes during job execution", () => {
      installPrefixedOutput();

      runWithJobContext({ jobId: "capture-test", traceId: "t1" }, () => {
        process.stdout.write("hello from job\n");
      });

      const logs = getAndClearJobLogs("capture-test");
      expect(logs.length).toBe(1);
      expect(logs[0].stream).toBe("stdout");
      expect(logs[0].text).toContain("hello from job");
    });

    it("captures stderr writes during job execution", () => {
      installPrefixedOutput();

      runWithJobContext({ jobId: "stderr-test", traceId: "t2" }, () => {
        process.stderr.write("error from job\n");
      });

      const logs = getAndClearJobLogs("stderr-test");
      expect(logs.length).toBe(1);
      expect(logs[0].stream).toBe("stderr");
      expect(logs[0].text).toContain("error from job");
    });

    it("does not capture writes outside job context", () => {
      installPrefixedOutput();

      process.stdout.write("outside context\n");

      const logs = getAndClearJobLogs("nonexistent");
      expect(logs).toEqual([]);
    });

    it("isolates logs per job", () => {
      installPrefixedOutput();

      runWithJobContext({ jobId: "job-a", traceId: "ta" }, () => {
        process.stdout.write("from job A\n");
      });

      runWithJobContext({ jobId: "job-b", traceId: "tb" }, () => {
        process.stdout.write("from job B\n");
      });

      const logsA = getAndClearJobLogs("job-a");
      const logsB = getAndClearJobLogs("job-b");

      expect(logsA.length).toBe(1);
      expect(logsA[0].text).toContain("from job A");
      expect(logsB.length).toBe(1);
      expect(logsB[0].text).toContain("from job B");
    });

    it("clears logs after getAndClearJobLogs", () => {
      installPrefixedOutput();

      runWithJobContext({ jobId: "clear-test", traceId: "tc" }, () => {
        process.stdout.write("data\n");
      });

      const first = getAndClearJobLogs("clear-test");
      expect(first.length).toBe(1);

      const second = getAndClearJobLogs("clear-test");
      expect(second).toEqual([]);
    });
  });
});

import { PromptVersion } from "@/prompt/PromptVersion";
import { PromptType, PromptVersionData } from "@/prompt/types";
import { PromptValidationError } from "@/prompt/errors";
import { logger } from "@/utils/logger";
import * as OpikApi from "@/rest_api/api";
import { MockInstance } from "vitest";

describe("PromptVersion", () => {
  let loggerInfoSpy: MockInstance<typeof logger.info>;

  beforeEach(() => {
    loggerInfoSpy = vi.spyOn(logger, "info");
  });

  afterEach(() => {
    loggerInfoSpy.mockRestore();
  });

  describe("constructor", () => {
    it("should create PromptVersion with all fields", () => {
      const data: PromptVersionData = {
        versionId: "version-123",
        name: "test-prompt",
        prompt: "Hello {{name}}!",
        commit: "abc123de",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        metadata: { version: "1.0" },
        changeDescription: "Initial version",
        createdAt: new Date("2024-01-01T10:00:00Z"),
        createdBy: "test-user@example.com",
      };

      const version = new PromptVersion(data);

      expect(version.id).toBe("version-123");
      expect(version.name).toBe("test-prompt");
      expect(version.prompt).toBe("Hello {{name}}!");
      expect(version.commit).toBe("abc123de");
      expect(version.type).toBe(PromptType.MUSTACHE);
      expect(version.metadata).toEqual({ version: "1.0" });
      expect(version.changeDescription).toBe("Initial version");
      expect(version.createdAt).toEqual(new Date("2024-01-01T10:00:00Z"));
      expect(version.createdBy).toBe("test-user@example.com");
    });

    it("should create PromptVersion with required fields only", () => {
      const data: PromptVersionData = {
        versionId: "version-123",
        name: "minimal-prompt",
        prompt: "Minimal template",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      };

      const version = new PromptVersion(data);

      expect(version.id).toBe("version-123");
      expect(version.name).toBe("minimal-prompt");
      expect(version.prompt).toBe("Minimal template");
      expect(version.commit).toBe("commit123");
      expect(version.type).toBe(PromptType.MUSTACHE);
      expect(version.metadata).toBeUndefined();
      expect(version.changeDescription).toBeUndefined();
      expect(version.createdAt).toBeUndefined();
      expect(version.createdBy).toBeUndefined();
    });

    it("should create PromptVersion with JINJA2 type", () => {
      const data: PromptVersionData = {
        versionId: "version-123",
        name: "jinja-prompt",
        prompt: "Hello {{ name }}!",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.JINJA2,
      };

      const version = new PromptVersion(data);

      expect(version.type).toBe(PromptType.JINJA2);
    });

    it("should create PromptVersion with complex metadata", () => {
      const metadata = {
        config: {
          model: "gpt-4",
          temperature: 0.7,
        },
        tags: ["production", "verified"],
        counts: [1, 2, 3],
      };

      const data: PromptVersionData = {
        versionId: "version-123",
        name: "complex-prompt",
        prompt: "Test",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        metadata,
      };

      const version = new PromptVersion(data);

      expect(version.metadata).toEqual(metadata);
    });
  });

  describe("format", () => {
    it("should delegate formatting to formatPromptTemplate", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "test-prompt",
        prompt: "Hello {{name}}!",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const result = version.format({ name: "World" });

      expect(result).toBe("Hello World!");
    });

    it("should format JINJA2 templates", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "jinja-prompt",
        prompt: "Hello {{ name }}!",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.JINJA2,
      });

      const result = version.format({ name: "Jinja" });

      expect(result).toBe("Hello Jinja!");
    });

    it("should handle multiple variables", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "multi-var-prompt",
        prompt: "Hello {{name}}, your score is {{score}}!",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const result = version.format({ name: "Alice", score: "95" });

      expect(result).toBe("Hello Alice, your score is 95!");
    });
  });

  describe("getVersionAge", () => {
    it("should return human-readable age for recent dates", () => {
      const now = new Date();
      const twoDaysAgo = new Date(now.getTime() - 2 * 24 * 60 * 60 * 1000);

      const version = new PromptVersion({
        versionId: "version-123",
        name: "recent-prompt",
        prompt: "Test",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: twoDaysAgo,
      });

      const age = version.getVersionAge();

      expect(age).toContain("2 days ago");
    });

    it("should return age for old dates", () => {
      const oneMonthAgo = new Date();
      oneMonthAgo.setMonth(oneMonthAgo.getMonth() - 1);

      const version = new PromptVersion({
        versionId: "version-123",
        name: "old-prompt",
        prompt: "Test",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: oneMonthAgo,
      });

      const age = version.getVersionAge();

      expect(age).toContain("ago");
      expect(age).toMatch(/month|about/);
    });

    it("should return age for various date ranges", () => {
      const testCases = [
        { daysAgo: 1, expectedPattern: /1 day ago/ },
        { daysAgo: 7, expectedPattern: /7 days ago/ },
        { daysAgo: 14, expectedPattern: /14 days ago|2 weeks ago/ },
        { daysAgo: 30, expectedPattern: /month|about/ },
      ];

      testCases.forEach(({ daysAgo, expectedPattern }) => {
        const date = new Date();
        date.setDate(date.getDate() - daysAgo);

        const version = new PromptVersion({
          versionId: "version-123",
          name: "test-prompt",
          prompt: "Test",
          commit: "commit123",
          promptId: "prompt-123",
          type: PromptType.MUSTACHE,
          createdAt: date,
        });

        const age = version.getVersionAge();
        expect(age).toMatch(expectedPattern);
      });
    });

    it('should return "Unknown" for missing dates', () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "no-date-prompt",
        prompt: "Test",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        // No createdAt
      });

      const age = version.getVersionAge();

      expect(age).toBe("Unknown");
    });

    it('should return "Unknown" for undefined createdAt', () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "undefined-date-prompt",
        prompt: "Test",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: undefined,
      });

      const age = version.getVersionAge();

      expect(age).toBe("Unknown");
    });
  });

  describe("getVersionInfo", () => {
    it("should format version info with all fields present", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "full-info-prompt",
        prompt: "Test",
        commit: "abc123de",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: new Date("2024-01-15T10:30:00Z"),
        createdBy: "user@example.com",
        changeDescription: "Added new feature",
      });

      const info = version.getVersionInfo();

      expect(info).toBe(
        "[abc123de] 2024-01-15 by user@example.com - Added new feature"
      );
    });

    it("should format version info with partial fields", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "partial-prompt",
        prompt: "Test",
        commit: "def456gh",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: new Date("2024-02-20T15:45:00Z"),
      });

      const info = version.getVersionInfo();

      expect(info).toBe("[def456gh] 2024-02-20");
    });

    it("should format version info with only commit", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "minimal-info-prompt",
        prompt: "Test",
        commit: "xyz789ab",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const info = version.getVersionInfo();

      expect(info).toBe("[xyz789ab]");
    });

    it("should format version info without createdAt", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "no-date-prompt",
        prompt: "Test",
        commit: "commit123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdBy: "developer@example.com",
        changeDescription: "Bug fix",
      });

      const info = version.getVersionInfo();

      expect(info).toBe("[commit123] by developer@example.com - Bug fix");
    });

    it("should format version info without createdBy", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "no-author-prompt",
        prompt: "Test",
        commit: "commit456",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: new Date("2024-03-10T08:00:00Z"),
        changeDescription: "Performance improvement",
      });

      const info = version.getVersionInfo();

      expect(info).toBe("[commit456] 2024-03-10 - Performance improvement");
    });

    it("should format version info without changeDescription", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "no-desc-prompt",
        prompt: "Test",
        commit: "commit789",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: new Date("2024-04-05T12:00:00Z"),
        createdBy: "admin@example.com",
      });

      const info = version.getVersionInfo();

      expect(info).toBe("[commit789] 2024-04-05 by admin@example.com");
    });

    it("should format date in YYYY-MM-DD format", () => {
      const version = new PromptVersion({
        versionId: "version-123",
        name: "date-format-prompt",
        prompt: "Test",
        commit: "commit999",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
        createdAt: new Date("2024-05-25T23:59:59Z"),
      });

      const info = version.getVersionInfo();

      expect(info).toContain("2024-05-25");
      expect(info).not.toContain("T");
      expect(info).not.toContain("23:59:59");
    });
  });

  describe("compareTo", () => {
    it("should return unified diff output", () => {
      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: "Hello {{name}}!",
        commit: "commit1",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: "Hi {{name}}!",
        commit: "commit2",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      expect(diff).toContain("Current version");
      expect(diff).toContain("Other version");
      expect(diff).toContain("[commit1]");
      expect(diff).toContain("[commit2]");
    });

    it("should log diff to terminal via logger", () => {
      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: "Original text",
        commit: "abc123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: "Modified text",
        commit: "def456",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      version1.compareTo(version2);

      expect(loggerInfoSpy).toHaveBeenCalledWith(
        expect.stringContaining("Prompt version comparison:")
      );
    });

    it("should verify diff format contains version labels", () => {
      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: "Line 1\nLine 2\nLine 3",
        commit: "commit-a",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: "Line 1\nModified Line 2\nLine 3",
        commit: "commit-b",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      expect(diff).toContain("[commit-a]");
      expect(diff).toContain("[commit-b]");
      expect(diff).toContain("Current version");
      expect(diff).toContain("Other version");
    });

    it("should show diff for identical versions", () => {
      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: "Same content",
        commit: "commit1",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: "Same content",
        commit: "commit2",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      // Diff should indicate no changes
      expect(diff).toBeDefined();
      expect(diff).toContain("[commit1]");
      expect(diff).toContain("[commit2]");
    });

    it("should handle large template differences", () => {
      const largeTemplate1 = Array(50)
        .fill("Line")
        .map((line, i) => `${line} ${i}`)
        .join("\n");

      const largeTemplate2 = Array(50)
        .fill("Modified")
        .map((line, i) => `${line} ${i}`)
        .join("\n");

      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "large-prompt",
        prompt: largeTemplate1,
        commit: "large1",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "large-prompt",
        prompt: largeTemplate2,
        commit: "large2",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      expect(diff).toBeDefined();
      expect(diff.length).toBeGreaterThan(0);
    });

    it("should handle multiline template differences", () => {
      const template1 = `Hello {{name}}!
Welcome to our service.
Please enjoy your stay.`;

      const template2 = `Hello {{name}}!
Welcome to our amazing service.
Please enjoy your extended stay.`;

      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "multiline-prompt",
        prompt: template1,
        commit: "multi1",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "multiline-prompt",
        prompt: template2,
        commit: "multi2",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      expect(diff).toContain("amazing");
      expect(diff).toContain("extended");
    });

    it("should compare multimodal content with text, images, and show diff markers", () => {
      const messages1 = [
        {
          role: "user",
          content: [
            { type: "text", text: "Describe the image in detail." },
            {
              type: "image_url",
              image_url: {
                url: "https://picsum.photos/id/237/200/300",
                detail: "high",
                width: 800,
              },
            },
          ],
        },
      ];

      const messages2 = [
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Please describe the image in detail, including colors.",
            },
            {
              type: "image_url",
              image_url: {
                url: "https://picsum.photos/id/237/200/500",
                detail: "low",
                height: 600,
              },
              my_metadata: "my_metadata",
            },
          ],
        },
      ];

      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: JSON.stringify(messages1),
        commit: "abc123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: JSON.stringify(messages2),
        commit: "def456",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      // Verify structure
      expect(diff).toContain("Message 1 [user]:");
      expect(diff).toContain("Type: text");
      expect(diff).toContain("Type: image_url");

      // Verify diff markers (- from version2, + from version1)
      expect(diff).toContain("+     Describe the image in detail.");
      expect(diff).toContain(
        "-     Please describe the image in detail, including colors."
      );
      expect(diff).toContain("+     URL: https://picsum.photos/id/237/200/300");
      expect(diff).toContain("-     URL: https://picsum.photos/id/237/200/500");

      // Verify property changes
      expect(diff).toContain("+     detail: high");
      expect(diff).toContain("-     detail: low");
      expect(diff).toContain("+     width: 800");
      expect(diff).toContain("-     height: 600");
      expect(diff).toContain("-     my_metadata: my_metadata");
    });

    it("should handle video content and additional properties", () => {
      const messages1 = [
        {
          role: "user",
          content: [
            {
              type: "video_url",
              video_url: {
                url: "https://example.com/video1.mp4",
                mime_type: "video/mp4",
                duration: 120,
                format: "h264",
                custom_prop: "custom1",
              },
              timestamp: "2024-01-01",
            },
          ],
        },
      ];

      const messages2 = [
        {
          role: "user",
          content: [
            {
              type: "video_url",
              video_url: {
                url: "https://example.com/video2.mp4",
                mime_type: "video/webm",
                duration: 180,
                bitrate: 5000,
                custom_prop: "custom2",
              },
              timestamp: "2024-01-02",
            },
          ],
        },
      ];

      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: JSON.stringify(messages1),
        commit: "abc123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: JSON.stringify(messages2),
        commit: "def456",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      // Verify structure
      expect(diff).toContain("Message 1 [user]:");
      expect(diff).toContain("Type: video_url");

      // Verify URL changes
      expect(diff).toContain("+     URL: https://example.com/video1.mp4");
      expect(diff).toContain("-     URL: https://example.com/video2.mp4");

      // Verify property changes with diff markers
      expect(diff).toContain("+     mime_type: video/mp4");
      expect(diff).toContain("-     mime_type: video/webm");
      expect(diff).toContain("+     duration: 120");
      expect(diff).toContain("-     duration: 180");
      expect(diff).toContain("+     format: h264");
      expect(diff).toContain("-     bitrate: 5000");
      expect(diff).toContain("+     custom_prop: custom1");
      expect(diff).toContain("-     custom_prop: custom2");
      expect(diff).toContain("+     timestamp: 2024-01-01");
      expect(diff).toContain("-     timestamp: 2024-01-02");
    });

    it("should handle mixed content types including unrecognized types", () => {
      const messages1 = [
        { role: "system", content: "You are helpful" },
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Compare these",
              language: "en",
            },
            {
              type: "image_url",
              image_url: { url: "https://example.com/img1.jpg" },
            },
            {
              type: "audio",
              audio_data: "base64data",
            },
          ],
        },
      ];

      const messages2 = [
        { role: "system", content: "You are friendly" },
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Compare those",
              language: "fr",
            },
            {
              type: "image_url",
              image_url: { url: "https://example.com/img2.jpg" },
            },
            {
              type: "audio",
              audio_data: "different_base64data",
            },
          ],
        },
      ];

      const version1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: JSON.stringify(messages1),
        commit: "abc123de",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const version2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: JSON.stringify(messages2),
        commit: "def456gh",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff = version1.compareTo(version2);

      // Verify commit hashes in headers
      expect(diff).toContain("abc123de");
      expect(diff).toContain("def456gh");

      // Verify multiple messages structure
      expect(diff).toContain("Message 1 [system]");
      expect(diff).toContain("Message 2 [user]");

      // Verify system message diff
      expect(diff).toContain("+   You are helpful");
      expect(diff).toContain("-   You are friendly");

      // Verify text content diff with diff markers
      expect(diff).toContain("+     Compare these");
      expect(diff).toContain("-     Compare those");
      expect(diff).toContain("+     language: en");
      expect(diff).toContain("-     language: fr");

      // Verify image URL changes with diff markers
      expect(diff).toContain("Type: image_url");
      expect(diff).toContain("+     URL: https://example.com/img1.jpg");
      expect(diff).toContain("-     URL: https://example.com/img2.jpg");

      // Verify unrecognized type handling
      expect(diff).toContain("Type: audio");
      expect(diff).toContain("[Difference found in unrecognized content type]");
      expect(diff).not.toContain("base64data");
    });

    it("should handle edge cases (empty messages, no commits, identical prompts)", () => {
      // Test with empty vs non-empty
      const emptyVersion = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: JSON.stringify([]),
        commit: "unknown",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const nonEmptyVersion = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: JSON.stringify([{ role: "user", content: "Hello" }]),
        commit: "unknown",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff1 = emptyVersion.compareTo(nonEmptyVersion);
      // Verify structure and added content with diff marker
      expect(diff1).toContain("Message 1 [user]");
      expect(diff1).toContain("-   Hello");
      expect(diff1).toContain("unknown");

      // Test identical prompts
      const messages = [{ role: "system", content: "You are helpful" }];

      const identicalVersion1 = new PromptVersion({
        versionId: "version-1",
        name: "test-prompt",
        prompt: JSON.stringify(messages),
        commit: "abc123",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const identicalVersion2 = new PromptVersion({
        versionId: "version-2",
        name: "test-prompt",
        prompt: JSON.stringify(messages),
        commit: "def456",
        promptId: "prompt-123",
        type: PromptType.MUSTACHE,
      });

      const diff2 = identicalVersion1.compareTo(identicalVersion2);
      expect(diff2).toBeDefined();
      expect(diff2).toContain("abc123");
      expect(diff2).toContain("def456");
      // For identical content, there should be no diff markers, just context
      expect(diff2).toContain("You are helpful");
    });
  });

  describe("fromApiResponse", () => {
    it("should create PromptVersion from valid API response", () => {
      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Hello {{name}}!",
        type: "mustache",
        createdAt: new Date("2024-01-01T10:00:00Z"),
        createdBy: "user@example.com",
        metadata: { version: "1.0" },
        changeDescription: "Initial version",
      };

      const version = PromptVersion.fromApiResponse("test-prompt", apiResponse);

      expect(version).toBeInstanceOf(PromptVersion);
      expect(version.id).toBe("version-id");
      expect(version.name).toBe("test-prompt");
      expect(version.prompt).toBe("Hello {{name}}!");
      expect(version.commit).toBe("abc123de");
      expect(version.type).toBe(PromptType.MUSTACHE);
      expect(version.metadata).toEqual({ version: "1.0" });
      expect(version.changeDescription).toBe("Initial version");
      expect(version.createdAt).toEqual(new Date("2024-01-01T10:00:00Z"));
      expect(version.createdBy).toBe("user@example.com");
    });

    it("should handle missing optional fields in API response", () => {
      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "commit123",
        template: "Minimal template",
        type: "mustache",
      };

      const version = PromptVersion.fromApiResponse(
        "minimal-prompt",
        apiResponse
      );

      expect(version).toBeInstanceOf(PromptVersion);
      expect(version.id).toBe("version-id");
      expect(version.name).toBe("minimal-prompt");
      expect(version.prompt).toBe("Minimal template");
      expect(version.commit).toBe("commit123");
      expect(version.metadata).toBeUndefined();
      expect(version.changeDescription).toBeUndefined();
      expect(version.createdAt).toBeUndefined();
      expect(version.createdBy).toBeUndefined();
    });

    it("should throw PromptValidationError when template is missing", () => {
      const apiResponse = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "commit123",
        type: "mustache",
        // Missing template
      } as OpikApi.PromptVersionDetail;

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow(PromptValidationError);

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow("Invalid API response: missing required field 'template'");
    });

    it("should throw PromptValidationError when commit is missing", () => {
      const apiResponse = {
        id: "version-id",
        promptId: "prompt-id",
        template: "Test template",
        type: "mustache",
        // Missing commit
      } as OpikApi.PromptVersionDetail;

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow(PromptValidationError);

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow("Invalid API response: missing required field 'commit'");
    });

    it("should throw PromptValidationError when promptId is missing", () => {
      const apiResponse = {
        id: "version-id",
        template: "Test template",
        commit: "commit123",
        type: "mustache",
        // Missing promptId
      } as OpikApi.PromptVersionDetail;

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow(PromptValidationError);

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow("Invalid API response: missing required field 'promptId'");
    });

    it("should throw PromptValidationError when id is missing", () => {
      const apiResponse = {
        promptId: "prompt-id",
        template: "Test template",
        commit: "commit123",
        type: "mustache",
        // Missing id
      } as OpikApi.PromptVersionDetail;

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow(PromptValidationError);

      expect(() =>
        PromptVersion.fromApiResponse("test-prompt", apiResponse)
      ).toThrow("Invalid API response: missing required field 'id'");
    });

    it("should default to MUSTACHE type when type is undefined", () => {
      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "commit123",
        template: "Test template",
        // type is undefined
      };

      const version = PromptVersion.fromApiResponse("test-prompt", apiResponse);

      expect(version.type).toBe(PromptType.MUSTACHE);
    });

    it("should parse createdAt date correctly", () => {
      const dateString = "2024-03-15T14:30:45Z";
      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "commit123",
        template: "Test template",
        type: "mustache",
        createdAt: new Date(dateString),
      };

      const version = PromptVersion.fromApiResponse("test-prompt", apiResponse);

      expect(version.createdAt).toEqual(new Date(dateString));
    });

    it("should handle JINJA2 type from API response", () => {
      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "commit123",
        template: "Hello {{ name }}!",
        type: "jinja2",
      };

      const version = PromptVersion.fromApiResponse(
        "jinja-prompt",
        apiResponse
      );

      expect(version.type).toBe(PromptType.JINJA2);
    });

    it("should preserve complex metadata from API response", () => {
      const complexMetadata = {
        config: {
          model: "gpt-4",
          temperature: 0.7,
          features: {
            streaming: true,
            cache: false,
          },
        },
        tags: ["production", "verified"],
        counts: [1, 2, 3, 4, 5],
      };

      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "commit123",
        template: "Test template",
        type: "mustache",
        metadata: complexMetadata,
      };

      const version = PromptVersion.fromApiResponse("test-prompt", apiResponse);

      expect(version.metadata).toEqual(complexMetadata);
    });
  });
});

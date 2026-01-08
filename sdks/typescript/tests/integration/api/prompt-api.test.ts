import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { PromptValidationError } from "@/prompt/errors";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Prompt Real API Integration", () => {
  let client: Opik;
  const createdPromptIds: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterEach(async () => {
    if (createdPromptIds.length > 0) {
      try {
        await client.deletePrompts(createdPromptIds);
      } catch (error) {
        console.warn(`Failed to cleanup prompts:`, error);
      }
      createdPromptIds.length = 0;
    }
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  describe("Basic Lifecycle", () => {
    it("should handle complete prompt lifecycle: create → format → update → retrieve → delete", async () => {
      const promptName = `test-lifecycle-${Date.now()}`;

      // CREATE
      const created = await client.createPrompt({
        name: promptName,
        prompt: "Hello {{name}}, welcome to {{place}}!",
        metadata: { author: "integration-test", version: "1.0" },
      });
      createdPromptIds.push(created.id);

      expect(created.name).toBe(promptName);
      expect(created.prompt).toBe("Hello {{name}}, welcome to {{place}}!");
      expect(created.metadata).toEqual({
        author: "integration-test",
        version: "1.0",
      });

      // FORMAT
      const formatted = created.format({
        name: "Alice",
        place: "Wonderland",
      });
      expect(formatted).toBe("Hello Alice, welcome to Wonderland!");

      // UPDATE (create new version)
      const updated = await client.createPrompt({
        name: promptName,
        prompt: "Hi {{name}}, welcome to {{place}}!",
        metadata: { author: "integration-test", version: "2.0" },
      });

      expect(updated.name).toBe(promptName);
      expect(updated.prompt).toBe("Hi {{name}}, welcome to {{place}}!");

      // RETRIEVE latest version
      const retrieved = await client.getPrompt({ name: promptName });
      expect(retrieved).not.toBeNull();
      expect(retrieved?.prompt).toBe("Hi {{name}}, welcome to {{place}}!");
      expect(retrieved?.metadata?.version).toBe("2.0");

      // DELETE - both versions should have same parent prompt ID
      expect(created.id).toBe(updated.id); // Verify they share the same promptId
      await client.deletePrompts([created.id]);

      // Verify deletion - should return null since prompt is deleted
      const deletedPrompt = await client.getPrompt({ name: promptName });
      expect(deletedPrompt).toBeNull();

      // Remove from cleanup list since we already deleted
      createdPromptIds.length = 0;
    }, 30000); // 30s timeout for API operations

    it("should handle prompt with complex metadata", async () => {
      const promptName = `test-metadata-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Question: {{question}}\nContext: {{context}}",
        metadata: {
          model: "gpt-4",
          temperature: 0.7,
          max_tokens: 150,
          use_case: "qa",
          tags: ["production", "v2"],
          config: {
            retry: true,
            timeout: 30000,
          },
        },
      });
      createdPromptIds.push(prompt.id);

      expect(prompt.metadata).toMatchObject({
        model: "gpt-4",
        temperature: 0.7,
        max_tokens: 150,
        use_case: "qa",
      });

      // Retrieve and verify metadata persisted
      const retrieved = await client.getPrompt({ name: promptName });
      expect(retrieved?.metadata).toMatchObject({
        model: "gpt-4",
        temperature: 0.7,
      });
    }, 30000);
  });

  describe("Version Management", () => {
    it("should track version history and allow retrieval of all versions", async () => {
      const promptName = `test-versions-${Date.now()}`;

      // Create initial version
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1: {{text}}",
        metadata: { version: "1.0" },
      });
      createdPromptIds.push(v1.id);

      // Create version 2
      await client.createPrompt({
        name: promptName,
        prompt: "Version 2: {{text}}",
        metadata: { version: "2.0" },
      });

      // Create version 3
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: "Version 3: {{text}}",
        metadata: { version: "3.0" },
      });

      // Get all versions
      const versions = await v3.getVersions();

      expect(versions.length).toBeGreaterThanOrEqual(3);

      // Verify versions are sorted by creation (newest first)
      const prompts = versions.map((v) => v.prompt);
      expect(prompts).toContain("Version 1: {{text}}");
      expect(prompts).toContain("Version 2: {{text}}");
      expect(prompts).toContain("Version 3: {{text}}");

      // Latest version should be first
      expect(versions[0].prompt).toBe("Version 3: {{text}}");
    }, 30000);

    it("should get specific version by commit hash", async () => {
      const promptName = `test-get-version-${Date.now()}`;

      // Create multiple versions
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Original: {{message}}",
      });
      createdPromptIds.push(v1.id);

      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Updated: {{message}}",
      });

      // Get specific version by commit
      const specificVersion = await v2.getVersion(v1.commit!);

      expect(specificVersion).not.toBeNull();
      expect(specificVersion?.prompt).toBe("Original: {{message}}");
      expect(specificVersion?.commit).toBe(v1.commit);
    }, 30000);

    it("should restore previous version (rollback scenario)", async () => {
      const promptName = `test-restore-${Date.now()}`;

      // Create original version
      const original = await client.createPrompt({
        name: promptName,
        prompt: "Original template: {{input}}",
        metadata: { stable: true },
      });
      createdPromptIds.push(original.id);

      // Create "bad" version
      const bad = await client.createPrompt({
        name: promptName,
        prompt: "Bad template: {{wrong_variable}}",
        metadata: { stable: false },
      });

      // Get all versions
      const versions = await bad.getVersions();

      // Find the original version
      const originalVersion = versions.find(
        (v) => v.commit === original.commit
      );
      expect(originalVersion).toBeDefined();

      // Restore original version
      const restored = await bad.useVersion(originalVersion!);

      expect(restored.prompt).toBe("Original template: {{input}}");
      expect(restored.metadata?.stable).toBe(true);

      // Verify latest version is now the restored one
      const latest = await client.getPrompt({ name: promptName });
      expect(latest?.prompt).toBe("Original template: {{input}}");
    }, 30000);

    it("should handle version age calculations", async () => {
      const promptName = `test-version-age-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test {{variable}}",
      });
      createdPromptIds.push(prompt.id);

      const versions = await prompt.getVersions();
      expect(versions.length).toBeGreaterThan(0);

      // Version should have a creation timestamp and age
      const latestVersion = versions[0];
      expect(latestVersion.createdAt).toBeDefined();
      expect(latestVersion.createdAt).toBeInstanceOf(Date);

      // getVersionAge() returns a human-readable string like "2 days ago"
      const age = latestVersion.getVersionAge();
      expect(typeof age).toBe("string");
      expect(age.length).toBeGreaterThan(0);
    }, 30000);

    it("should filter versions", async () => {
      const promptName = `test-filter-tags-${Date.now()}`;

      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1 template",
      });
      createdPromptIds.push(v1.id);
      await client.updatePromptVersionTags([v1.versionId], {
        tags: ["production", "stable"],
      });
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Version 2 template",
      });
      createdPromptIds.push(v2.id);
      await client.updatePromptVersionTags([v2.versionId], {
        tags: ["experimental", "beta"],
      });
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: "Version 3 template",
      });
      createdPromptIds.push(v3.id);
      await client.updatePromptVersionTags([v3.versionId], {
        tags: ["production", "tested"],
      });

      const productionVersions = await v1.getVersions({
        filters: JSON.stringify([
          { field: "tags", operator: "contains", value: "production" },
        ]),
      });

      expect(productionVersions.length).toEqual(2);
      const ids = productionVersions.map((v) => v.id);
      expect(ids).toContain(v1.versionId);
      expect(ids).toContain(v3.versionId);
      expect(ids).not.toContain(v2.versionId);
    }, 30000);

    it("should search versions", async () => {
      const promptName = `test-search-template-${Date.now()}`;
      const searchTerm = `unique-search-term-${Date.now()}`;

      const v1 = await client.createPrompt({
        name: promptName,
        prompt: `This template contains ${searchTerm} for testing`,
      });
      createdPromptIds.push(v1.id);
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "This template has different content",
      });
      createdPromptIds.push(v2.id);
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: `Another template with ${searchTerm} included`,
      });
      createdPromptIds.push(v3.id);

      // Search for versions containing the search term
      const searchResults = await v1.getVersions({
        search: searchTerm,
      });

      // Should return v1 and v3
      expect(searchResults.length).toEqual(2);
      const ids = searchResults.map((v) => v.id);
      expect(ids).toContain(v1.versionId);
      expect(ids).toContain(v3.versionId);
      expect(ids).not.toContain(v2.versionId);
    }, 30000);

    it("should sort versions", async () => {
      const promptName = `test-sort-desc-${Date.now()}`;
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1",
      });
      createdPromptIds.push(v1.id);
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Version 2",
      });
      createdPromptIds.push(v2.id);
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: "Version 3",
      });
      createdPromptIds.push(v3.id);

      const sortedVersions = await v1.getVersions({
        sorting: JSON.stringify([{ field: "template", direction: "DESC" }]),
      });

      expect(sortedVersions.length).toBe(3);
      expect(sortedVersions[0].id).toBe(v3.versionId);
      expect(sortedVersions[1].id).toBe(v2.versionId);
      expect(sortedVersions[2].id).toBe(v1.versionId);
    }, 30000);
  });

  describe("Properties Management", () => {
    it("should update prompt properties (name, description, tags)", async () => {
      const originalName = `test-update-props-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: originalName,
        prompt: "Test {{input}}",
      });
      createdPromptIds.push(prompt.id);

      // Update properties
      const updatedName = `${originalName}-renamed`;
      await prompt.updateProperties({
        name: updatedName,
        description: "Updated description",
        tags: ["production", "v2", "qa"],
      });

      // Verify local state updated
      expect(prompt.name).toBe(updatedName);
      expect(prompt.description).toBe("Updated description");
      expect(prompt.tags).toEqual(["production", "v2", "qa"]);

      // Verify backend state updated
      const retrieved = await client.getPrompt({ name: updatedName });
      expect(retrieved).not.toBeNull();
      expect(retrieved?.name).toBe(updatedName);
      expect(retrieved?.description).toBe("Updated description");
      expect(retrieved?.tags).toEqual(["qa", "production", "v2"]);
    }, 30000);

    it("should update only specific properties", async () => {
      const promptName = `test-partial-update-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test {{value}}",
      });
      createdPromptIds.push(prompt.id);

      // Update only tags
      await prompt.updateProperties({
        tags: ["new-tag"],
      });

      expect(prompt.name).toBe(promptName); // Name unchanged
      expect(prompt.tags).toEqual(["new-tag"]);

      // Update only description
      await prompt.updateProperties({
        description: "New description",
      });

      expect(prompt.description).toBe("New description");
      expect(prompt.tags).toEqual(["new-tag"]); // Tags unchanged
    }, 30000);

    it("should handle prompt deletion via instance method", async () => {
      const promptName = `test-delete-instance-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Delete me {{now}}",
      });
      createdPromptIds.push(prompt.id);

      // Delete via instance method
      await prompt.delete();

      // Verify deletion
      const deleted = await client.getPrompt({ name: promptName });
      expect(deleted).toBeNull();

      // Remove from cleanup list
      createdPromptIds.length = 0;
    }, 30000);
  });

  describe("Template Formatting", () => {
    it("should format templates with multiple variables", async () => {
      const promptName = `test-multi-vars-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt:
          "User: {{user_name}}\nRole: {{role}}\nQuestion: {{question}}\nContext: {{context}}",
      });
      createdPromptIds.push(prompt.id);

      const formatted = prompt.format({
        user_name: "Alice",
        role: "admin",
        question: "What is AI?",
        context: "Machine learning discussion",
      });

      expect(formatted).toBe(
        "User: Alice\nRole: admin\nQuestion: What is AI?\nContext: Machine learning discussion"
      );
    }, 30000);

    it("should format templates with numeric and boolean variables", async () => {
      const promptName = `test-mixed-types-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt:
          "Count: {{count}}, Score: {{score}}, Active: {{is_active}}, Name: {{name}}",
      });
      createdPromptIds.push(prompt.id);

      const formatted = prompt.format({
        count: 42,
        score: 95.5,
        is_active: true,
        name: "Test",
      });

      expect(formatted).toBe(
        "Count: 42, Score: 95.5, Active: true, Name: Test"
      );
    }, 30000);

    it("should throw error when required variables are missing", async () => {
      const promptName = `test-missing-vars-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Hello {{name}}, your score is {{score}}",
      });
      createdPromptIds.push(prompt.id);

      // Missing 'score' variable should throw
      expect(() => {
        prompt.format({ name: "Alice" });
      }).toThrow(PromptValidationError);
    }, 30000);

    it("should handle template with no variables", async () => {
      const promptName = `test-no-vars-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "This is a static template with no variables.",
      });
      createdPromptIds.push(prompt.id);

      const formatted = prompt.format({});
      expect(formatted).toBe("This is a static template with no variables.");
    }, 30000);
  });

  describe("Search & Discovery", () => {
    it("should search prompts with name filter", async () => {
      const prefix = `search-test-${Date.now()}`;

      // Create multiple prompts with common prefix
      const p1 = await client.createPrompt({
        name: `${prefix}-prompt-1`,
        prompt: "Template 1: {{var}}",
      });
      createdPromptIds.push(p1.id);

      const p2 = await client.createPrompt({
        name: `${prefix}-prompt-2`,
        prompt: "Template 2: {{var}}",
      });
      createdPromptIds.push(p2.id);

      const p3 = await client.createPrompt({
        name: `different-${Date.now()}`,
        prompt: "Different {{var}}",
      });
      createdPromptIds.push(p3.id);

      // Search with name filter
      const results = await client.searchPrompts(`name contains "${prefix}"`);

      expect(results.length).toBeGreaterThanOrEqual(2);

      const names = results.map((p) => p.name);
      expect(names).toContain(`${prefix}-prompt-1`);
      expect(names).toContain(`${prefix}-prompt-2`);
      expect(names).not.toContain(p3.name);
    }, 30000);

    it("should search prompts with tag filters", async () => {
      const timestamp = Date.now();

      // Create prompts with different tags
      const p1 = await client.createPrompt({
        name: `tagged-${timestamp}-1`,
        prompt: "Tagged 1 {{v}}",
      });
      createdPromptIds.push(p1.id);

      await p1.updateProperties({
        tags: ["production", "stable"],
      });

      const p2 = await client.createPrompt({
        name: `tagged-${timestamp}-2`,
        prompt: "Tagged 2 {{v}}",
      });
      createdPromptIds.push(p2.id);

      await p2.updateProperties({
        tags: ["experimental", "beta"],
      });

      // Search by tag
      const productionPrompts = await client.searchPrompts(
        'tags contains "production"'
      );

      const productionNames = productionPrompts.map((p) => p.name);
      expect(productionNames).toContain(`tagged-${timestamp}-1`);

      const experimentalPrompts = await client.searchPrompts(
        'tags contains "experimental"'
      );

      const experimentalNames = experimentalPrompts.map((p) => p.name);
      expect(experimentalNames).toContain(`tagged-${timestamp}-2`);
    }, 30000);

    it("should search prompts with multiple filters", async () => {
      const timestamp = Date.now();
      const baseName = `multi-filter-${timestamp}`;

      const prompt = await client.createPrompt({
        name: `${baseName}-qa`,
        prompt: "QA template {{q}}",
      });
      createdPromptIds.push(prompt.id);

      await prompt.updateProperties({
        tags: ["qa", "production"],
      });

      // Small delay to allow backend to index tags
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Search with multiple conditions
      const results = await client.searchPrompts(
        `name contains "${baseName}" AND tags contains "qa"`
      );

      expect(results.length).toBeGreaterThanOrEqual(1);
      const found = results.find((p) => p.name === `${baseName}-qa`);
      expect(found).toBeDefined();

      // Tags should be available after the delay
      expect(found?.tags).toBeDefined();
      expect(Array.isArray(found?.tags)).toBe(true);
      if (found?.tags) {
        expect(found.tags).toContain("qa");
      }
    }, 30000);

    it("should return empty array when no prompts match filters", async () => {
      const results = await client.searchPrompts(
        `name contains "non-existent-prompt-${Date.now()}"`
      );

      expect(results).toEqual([]);
    }, 30000);
  });

  describe("Production Patterns", () => {
    it("should create prompt with tags and description, syncing them with backend", async () => {
      const promptName = `test-tags-desc-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test {{input}}",
        description: "Initial description",
        tags: ["initial", "test"],
      });
      createdPromptIds.push(prompt.id);

      // Verify local state
      expect(prompt.description).toBe("Initial description");
      expect(prompt.tags).toEqual(["initial", "test"]);

      // Verify backend state
      const retrieved = await client.getPrompt({ name: promptName });
      expect(retrieved?.description).toBe("Initial description");
      expect(retrieved?.tags).toContain("initial");
      expect(retrieved?.tags).toContain("test");
    }, 30000);

    it("should support A/B testing scenario with multiple versions", async () => {
      const promptName = `ab-test-${Date.now()}`;

      // Version A - control
      const versionA = await client.createPrompt({
        name: promptName,
        prompt: "Simple greeting: Hello {{name}}!",
        metadata: { variant: "A", type: "control" },
      });
      createdPromptIds.push(versionA.id);

      // Version B - experiment
      const versionB = await client.createPrompt({
        name: promptName,
        prompt: "Friendly greeting: Hey there {{name}}, how are you today?",
        metadata: { variant: "B", type: "experiment" },
      });

      // Simulate A/B test usage
      const testCases = [
        { variant: "A", expected: "Simple greeting: Hello Alice!" },
        {
          variant: "B",
          expected: "Friendly greeting: Hey there Alice, how are you today?",
        },
      ];

      for (const testCase of testCases) {
        const version =
          testCase.variant === "A"
            ? await versionA.getVersion(versionA.commit!)
            : versionB;

        const result = version!.format({ name: "Alice" });
        expect(result).toBe(testCase.expected);
      }

      // Winner is determined - keep version B
      const latest = await client.getPrompt({ name: promptName });
      expect(latest?.metadata?.variant).toBe("B");
    }, 30000);

    it("should handle prompt inheritance pattern", async () => {
      const basePromptName = `base-${Date.now()}`;
      const derivedPromptName = `derived-${Date.now()}`;

      // Base prompt template
      const basePrompt = await client.createPrompt({
        name: basePromptName,
        prompt: "System: {{system_message}}\nUser: {{user_input}}",
        metadata: { type: "base", category: "chat" },
      });
      createdPromptIds.push(basePrompt.id);

      // Derived prompt that extends base
      const derivedPrompt = await client.createPrompt({
        name: derivedPromptName,
        prompt:
          "System: You are a helpful assistant.\nUser: {{user_input}}\nContext: {{context}}",
        metadata: {
          type: "derived",
          parent: basePromptName,
          category: "chat",
        },
      });
      createdPromptIds.push(derivedPrompt.id);

      // Use derived prompt
      const formatted = derivedPrompt.format({
        user_input: "What is AI?",
        context: "Machine learning basics",
      });

      expect(formatted).toContain("helpful assistant");
      expect(formatted).toContain("What is AI?");
      expect(formatted).toContain("Machine learning basics");
    }, 30000);

    it("should handle rapid version updates (CI/CD scenario)", async () => {
      const promptName = `cicd-${Date.now()}`;

      // Simulate rapid deployment of prompt versions
      const versions = [];

      for (let i = 1; i <= 5; i++) {
        const version = await client.createPrompt({
          name: promptName,
          prompt: `Version ${i}: {{input}}`,
          metadata: {
            build: `build-${i}`,
            timestamp: new Date().toISOString(),
          },
        });

        if (i === 1) {
          createdPromptIds.push(version.id);
        }

        versions.push(version);
      }

      // Verify all versions are tracked
      const latestPrompt = versions[versions.length - 1];
      const history = await latestPrompt.getVersions();

      expect(history.length).toBeGreaterThanOrEqual(5);

      // Latest should be version 5
      expect(latestPrompt.prompt).toBe("Version 5: {{input}}");
      expect(latestPrompt.metadata?.build).toBe("build-5");
    }, 30000);

    it("should handle concurrent prompt usage (multi-user scenario)", async () => {
      const promptName = `concurrent-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Hello {{name}}, you have {{count}} messages.",
      });
      createdPromptIds.push(prompt.id);

      // Simulate multiple users formatting the same prompt concurrently
      const users = [
        { name: "Alice", count: 5 },
        { name: "Bob", count: 12 },
        { name: "Charlie", count: 0 },
        { name: "Diana", count: 3 },
      ];

      const results = await Promise.all(
        users.map(async (user) => {
          const formatted = prompt.format({
            name: user.name,
            count: user.count,
          });
          return { user: user.name, result: formatted };
        })
      );

      // Verify all results are correct
      expect(results[0].result).toBe("Hello Alice, you have 5 messages.");
      expect(results[1].result).toBe("Hello Bob, you have 12 messages.");
      expect(results[2].result).toBe("Hello Charlie, you have 0 messages.");
      expect(results[3].result).toBe("Hello Diana, you have 3 messages.");
    }, 30000);
  });

  describe("Error Handling", () => {
    it("should return null for non-existent prompt", async () => {
      const result = await client.getPrompt({
        name: `non-existent-${Date.now()}`,
      });

      expect(result).toBeNull();
    }, 30000);

    it("should return null for non-existent version", async () => {
      const promptName = `test-no-version-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test {{v}}",
      });
      createdPromptIds.push(prompt.id);

      const result = await prompt.getVersion("nonexistent123");
      expect(result).toBeNull();
    }, 30000);

    it("should handle empty search results gracefully", async () => {
      const results = await client.searchPrompts(
        `name = "definitely-does-not-exist-${Date.now()}"`
      );

      expect(results).toEqual([]);
    }, 30000);
  });

  describe("Update Prompt Version Tags Functionality", () => {
    it("should update prompt version tags in replace mode", async () => {
      const promptName = `test-tags-replace-${Date.now()}`;

      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Test replace template v1",
      });
      createdPromptIds.push(version1.id);
      await client.updatePromptVersionTags([version1.versionId], {
        tags: ["tag1", "tag2"],
      });

      const version2 = await client.createPrompt({
        name: promptName,
        prompt: "Test replace template v2",
      });
      createdPromptIds.push(version2.id);
      await client.updatePromptVersionTags([version2.versionId], {
        tags: ["tag3", "tag4"],
      });

      const newTags = ["tag5", "tag6"];
      await client.updatePromptVersionTags(
        [version1.versionId, version2.versionId],
        {
          tags: newTags,
          mergeTags: false,
        },
      );

      const updated = await client.getPrompt({ name: promptName });
      const versions = await updated!.getVersions();
      const updatedVersion1 = versions.find((v) => v.id === version1.versionId);
      expect(updatedVersion1?.tags).toEqual(expect.arrayContaining(newTags));
      expect(updatedVersion1?.tags?.length).toBe(newTags.length);
      const updatedVersion2 = versions.find((v) => v.id === version2.versionId);
      expect(updatedVersion2?.tags).toEqual(expect.arrayContaining(newTags));
      expect(updatedVersion2?.tags?.length).toBe(newTags.length);
    }, 30000);

    it("should update prompt version tags in default (replace) mode", async () => {
      const promptName = `test-tags-default-replace-${Date.now()}`;

      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Test default replace template v1",
      });
      createdPromptIds.push(version1.id);
      await client.updatePromptVersionTags([version1.versionId], {
        tags: ["tag1", "tag2"],
      });
      const version2 = await client.createPrompt({
        name: promptName,
        prompt: "Test default replace template v2",
      });
      createdPromptIds.push(version2.id);
      await client.updatePromptVersionTags([version2.versionId], {
        tags: ["tag3", "tag4"],
      });

      const newTags = ["tag5", "tag6"];
      await client.updatePromptVersionTags(
        [version1.versionId, version2.versionId],
        {
          tags: newTags,
        }
      );

      const updated = await client.getPrompt({ name: promptName });
      const versions = await updated!.getVersions();
      const updatedVersion1 = versions.find((v) => v.id === version1.versionId);
      expect(updatedVersion1?.tags).toEqual(expect.arrayContaining(newTags));
      expect(updatedVersion1?.tags?.length).toBe(newTags.length);
      const updatedVersion2 = versions.find((v) => v.id === version2.versionId);
      expect(updatedVersion2?.tags).toEqual(expect.arrayContaining(newTags));
      expect(updatedVersion2?.tags?.length).toBe(newTags.length);
    }, 30000);

    it("should clear all tags when empty array in default (replace) mode", async () => {
      const promptName = `test-tags-clear-empty-array-${Date.now()}`;

      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Test clear empty array",
      });
      createdPromptIds.push(version1.id);
      await client.updatePromptVersionTags([version1.versionId], {
        tags: ["tag1", "tag2"],
      });

      await client.updatePromptVersionTags([version1.versionId], {
        tags: [],
      });

      // Verify version-level tags were cleared (backend may return undefined or [])
      const versions = await version1.getVersions();
      const currentVersion = versions.find((v) => v.id === version1.versionId);
      expect(currentVersion?.tags ?? []).toEqual([]);
    }, 30000);

    it("should preserve all tags when null array in replace mode", async () => {
      const promptName = `test-tags-preserve-null-array-${Date.now()}`;

      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Test preserve null array",
      });
      createdPromptIds.push(version1.id);
      const tags1 = ["tag1", "tag2"];
      await client.updatePromptVersionTags([version1.versionId], {
        tags: tags1,
      });

      await client.updatePromptVersionTags([version1.versionId], {
        mergeTags: false,
      });

      const versions = await version1.getVersions();
      const updatedVersion1 = versions.find((v) => v.id === version1.versionId);
      expect(updatedVersion1?.tags).toEqual(expect.arrayContaining(tags1));
      expect(updatedVersion1?.tags?.length).toBe(tags1.length);
    }, 30000);

    it("should update prompt version tags in merge mode", async () => {
      const promptName = `test-tags-merge-${Date.now()}`;

      const tags1 = ["tag1", "tag2"];
      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Test merge template v1",
      });
      createdPromptIds.push(version1.id);
      await client.updatePromptVersionTags([version1.versionId], {
        tags: tags1,
      });

      const tags2 = ["tag3", "tag4"];
      const version2 = await client.createPrompt({
        name: promptName,
        prompt: "Test merge template v2",
      });
      createdPromptIds.push(version2.id);
      await client.updatePromptVersionTags([version2.versionId], {
        tags: tags2,
      });

      const additionalTags = ["tag5", "tag6"];
      await client.updatePromptVersionTags(
        [version1.versionId, version2.versionId],
        {
          tags: additionalTags,
          mergeTags: true,
        },
      );

      // Verify version-specific tags were merged (check via version history)
      const updated = await client.getPrompt({ name: promptName });
      const versions = await updated!.getVersions();
      const updatedVersion1 = versions.find((v) => v.id === version1.versionId);
      expect(updatedVersion1?.tags).toEqual(expect.arrayContaining([...tags1, ...additionalTags]));
      expect(updatedVersion1?.tags?.length).toBe(tags1.length + additionalTags.length);

      const updatedVersion2 = versions.find((v) => v.id === version2.versionId);
      expect(updatedVersion2?.tags).toEqual(expect.arrayContaining([...tags2, ...additionalTags]));
      expect(updatedVersion2?.tags?.length).toBe(tags2.length + additionalTags.length);
    }, 30000);
  });
});

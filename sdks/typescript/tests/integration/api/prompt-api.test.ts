import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { PromptValidationError } from "@/prompt/errors";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

// LOCAL TESTING: Force tests to run (skip check disabled)
describe("Prompt Real API Integration", () => {
  let client: Opik;
  const createdPromptIds: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    // if (!shouldRunApiTests) {
    //   return;
    // }

    // LOCAL TESTING: Configure for localhost:8080 with default workspace
    // Set a dummy API key if not provided (local testing may not require auth)
    if (!process.env.OPIK_API_KEY) {
      process.env.OPIK_API_KEY = "local-test-key";
    }

    client = new Opik({
      apiUrl: "http://localhost:8080/",
      workspaceName: "default",
    });
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

    it("should create prompt with tags", async () => {
      const promptName = `test-tags-${Date.now()}`;
      const tags = ["production", "v1", "baseline"];

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Hello {{name}}!",
        tags,
      });
      createdPromptIds.push(prompt.id);

      expect(prompt.tags).toEqual(expect.arrayContaining(tags));
      expect(prompt.tags?.length).toBe(tags.length);
    }, 30000);

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
      expect(prompt.tags).toEqual(expect.arrayContaining(["production", "v2", "qa"]));
      expect(prompt.tags?.length).toBe(3);

      // Verify backend state updated
      const retrieved = await client.getPrompt({ name: updatedName });
      expect(retrieved).not.toBeNull();
      expect(retrieved?.name).toBe(updatedName);
      expect(retrieved?.description).toBe("Updated description");
      expect(retrieved?.tags).toEqual(expect.arrayContaining(["qa", "production", "v2"]));
      expect(retrieved?.tags?.length).toBe(3);
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
      expect(prompt.tags).toEqual(expect.arrayContaining(["new-tag"]));
      expect(prompt.tags?.length).toBe(1);

      // Update only description
      await prompt.updateProperties({
        description: "New description",
      });

      expect(prompt.description).toBe("New description");
      expect(prompt.tags).toEqual(expect.arrayContaining(["new-tag"])); // Tags unchanged
      expect(prompt.tags?.length).toBe(1);
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
      expect(prompt.tags).toEqual(expect.arrayContaining(["initial", "test"]));
      expect(prompt.tags?.length).toBe(2);

      // Verify backend state
      const retrieved = await client.getPrompt({ name: promptName });
      expect(retrieved?.description).toBe("Initial description");
      expect(retrieved?.tags).toContain("initial");
      expect(retrieved?.tags).toContain("test");
      expect(retrieved?.tags?.length).toBe(2);
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
    it("should update prompt version tags in default (replace) mode", async () => {
      const promptName = `test-tags-default-replace-${Date.now()}`;

      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Test template v1",
      });
      createdPromptIds.push(version1.id);
      await client.updatePromptVersionTags([version1.versionId], {
        tags: ["tag1", "tag2"],
      });

      const version2 = await client.createPrompt({
        name: promptName,
        prompt: "Test template v2",
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

    it("should update prompt version tags in replace mode", async () => {
        const promptName = `test-tags-replace-${Date.now()}`;

          const version1 = await client.createPrompt({
              name: promptName,
              prompt: "Test template v1",
          });
          createdPromptIds.push(version1.id);
          await client.updatePromptVersionTags([version1.versionId], {
              tags: ["tag1", "tag2"],
          });

          const version2 = await client.createPrompt({
              name: promptName,
              prompt: "Test template v2",
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


    it("should update prompt version tags in merge mode", async () => {
      const promptName = `test-tags-merge-${Date.now()}`;
      const initialTags = ["tag1", "tag2"];

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test template",
        tags: initialTags, // Container-level only
      });
      createdPromptIds.push(prompt.id);

      // First set initial version-level tags
      await client.updatePromptVersionTags([prompt.versionId], {
        tags: initialTags,
      });

      // Wait for backend to process
      await new Promise((resolve) => setTimeout(resolve, 500));

      // Now merge additional tags (merge mode)
      const additionalTags = ["tag3", "tag4"];
      await client.updatePromptVersionTags([prompt.versionId], {
        tags: additionalTags,
        mergeTags: true,
      });

      // Wait for backend to process the merge
      await new Promise((resolve) => setTimeout(resolve, 1500));

      // Verify version-specific tags were merged (check via version history)
      const updated = await client.getPrompt({ name: promptName });
      const versions = await updated!.getVersions();
      const currentVersion = versions.find(v => v.id === prompt.versionId);

      // Version tags should be merged (union of both sets)
      expect(currentVersion?.tags).toEqual(
        expect.arrayContaining([...initialTags, ...additionalTags])
      );
      expect(currentVersion?.tags?.length).toBe(4);

      // Container-level tags should remain unchanged
      expect(updated?.tags).toEqual(expect.arrayContaining(initialTags));
      expect(updated?.tags?.length).toBe(initialTags.length);
    }, 30000);

    it("should update multiple version tags at once", async () => {
      const promptName = `test-multi-tags-${Date.now()}`;

      // Create two versions
      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Template v1",
        tags: ["v1"],
      });
      createdPromptIds.push(version1.id);

      const version2 = await client.createPrompt({
        name: promptName,
        prompt: "Template v2",
        tags: ["v2"],
      });

      // Update both version-specific tags with the same tags
      const newTags = ["production", "tested"];
      await client.updatePromptVersionTags(
        [version1.versionId, version2.versionId],
        {
          tags: newTags,
        }
      );

      // Verify both versions have the new version-specific tags
      const versions = await version1.getVersions();
      for (const version of versions) {
        expect(version.tags).toEqual(expect.arrayContaining(newTags));
        expect(version.tags?.length).toBe(newTags.length);
      }

      // Container-level tags should remain as "v2" (from latest version creation)
      const prompt = await client.getPrompt({ name: promptName });
      expect(prompt?.tags).toEqual(expect.arrayContaining(["v2"]));
    }, 30000);

    it("should clear all tags when empty array is provided", async () => {
      const promptName = `test-clear-tags-${Date.now()}`;
      const initialTags = ["tag1", "tag2", "tag3"];

      // Create prompt with container tags
      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Template with tags",
        tags: initialTags,
      });
      createdPromptIds.push(prompt.id);

      // Set initial version-level tags
      await client.updatePromptVersionTags([prompt.versionId], {
        tags: initialTags,
      });

      // Wait for backend to process
      await new Promise((resolve) => setTimeout(resolve, 500));

      // Clear all version-level tags with empty array
      await client.updatePromptVersionTags([prompt.versionId], {
        tags: [],
      });

      // Wait for backend indexing
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Verify version-level tags were cleared (backend may return undefined or [])
      const versions = await prompt.getVersions();
      const currentVersion = versions.find((v) => v.versionId === prompt.versionId);
      expect(currentVersion?.tags ?? []).toEqual([]);

      // Container-level tags should remain unchanged
      const updated = await client.getPrompt({ name: promptName });
      expect(updated?.tags).toEqual(expect.arrayContaining(initialTags));
    }, 30000);









    it("should preserve tags in version history", async () => {
      const promptName = `test-tags-history-${Date.now()}`;

      // Create version 1 with container tags
      const version1 = await client.createPrompt({
        name: promptName,
        prompt: "Template v1",
        tags: ["v1", "baseline"], // Container-level only
      });
      createdPromptIds.push(version1.id);
      // Explicitly set version-level tags for v1
      await client.updatePromptVersionTags([version1.versionId], {
        tags: ["v1", "baseline"],
      });

      // Create version 2 with different container tags
      const version2 = await client.createPrompt({
        name: promptName,
        prompt: "Template v2",
        tags: ["v2", "experimental"], // Container-level only
      });
      // Explicitly set version-level tags for v2
      await client.updatePromptVersionTags([version2.versionId], {
        tags: ["v2", "experimental"],
      });

      // Wait for backend indexing
      await new Promise((resolve) => setTimeout(resolve, 1500));

      // Get history and verify version-specific tags are preserved for each version
      const history = await version1.getVersions();

      expect(history.length).toBe(2);

      // Find versions in history
      const v1InHistory = history.find((v) => v.commit === version1.commit);
      const v2InHistory = history.find((v) => v.commit === version2.commit);

      // Each version should have its own version-specific tags preserved
      expect(v1InHistory?.tags).toEqual(expect.arrayContaining(["v1", "baseline"]));
      expect(v1InHistory?.tags?.length).toBe(2);
      expect(v2InHistory?.tags).toEqual(expect.arrayContaining(["v2", "experimental"]));
      expect(v2InHistory?.tags?.length).toBe(2);

      // Container-level tags should be from latest version
      const prompt = await client.getPrompt({ name: promptName });
      expect(prompt?.tags).toEqual(expect.arrayContaining(["v2", "experimental"]));
    }, 30000);

    it("should return tags as readonly property at both container and version level", async () => {
      const promptName = `test-tags-readonly-${Date.now()}`;
      const tags = ["tag1", "tag2"];

      // Create prompt with container tags
      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test template",
        tags, // Container-level only
      });
      createdPromptIds.push(prompt.id);

      // Explicitly set version-level tags
      await client.updatePromptVersionTags([prompt.versionId], {
        tags,
      });

      // Wait for backend indexing
      await new Promise((resolve) => setTimeout(resolve, 1500));

      // Verify container-level tags
      expect(prompt.tags).toEqual(expect.arrayContaining(tags));
      expect(prompt.tags?.length).toBe(tags.length);

      // Get versions and verify version-specific tags are also set
      const versions = await prompt.getVersions();
      expect(versions[0].tags).toEqual(expect.arrayContaining(tags));
      expect(versions[0].tags?.length).toBe(tags.length);
    }, 30000);
  });

  describe("Version History - Filtering, Sorting, and Searching", () => {
    it("should filter versions by tags", async () => {
      const promptName = `test-filter-tags-${Date.now()}`;

      // Create version 1 with container tags
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1 template",
        tags: ["production", "stable"], // Container-level only
      });
      createdPromptIds.push(v1.id);
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v1.versionId], {
        tags: ["production", "stable"],
      });

      // Create version 2 with container tags
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Version 2 template",
        tags: ["experimental", "beta"], // Container-level only
      });
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v2.versionId], {
        tags: ["experimental", "beta"],
      });

      // Create version 3 with container tags
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: "Version 3 template",
        tags: ["production", "tested"], // Container-level only
      });
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v3.versionId], {
        tags: ["production", "tested"],
      });

      // Small delay to ensure backend indexing of version tags
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Filter versions with "production" tag (version-level)
      const productionVersions = await v1.getVersions({
        filters: JSON.stringify([
          { field: "tags", operator: "contains", value: "production" },
        ]),
      });

      // Should only return v1 and v3
      expect(productionVersions.length).toBeGreaterThanOrEqual(2);
      const commits = productionVersions.map((v) => v.commit);
      expect(commits).toContain(v1.commit);
      expect(commits).toContain(v3.commit);
      expect(commits).not.toContain(v2.commit);
    }, 30000);

    it("should filter versions by multiple tags", async () => {
      const promptName = `test-filter-multi-tags-${Date.now()}`;

      // Create version 1 with container tags
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1",
        tags: ["production", "stable"], // Container-level only
      });
      createdPromptIds.push(v1.id);
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v1.versionId], {
        tags: ["production", "stable"],
      });

      // Create version 2 with container tags
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Version 2",
        tags: ["production", "experimental"], // Container-level only
      });
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v2.versionId], {
        tags: ["production", "experimental"],
      });

      // Create version 3 with container tags
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: "Version 3",
        tags: ["dev", "unstable"], // Container-level only
      });
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v3.versionId], {
        tags: ["dev", "unstable"],
      });

      // Small delay to ensure backend indexing of version tags
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Filter for versions with "stable" tag (version-level)
      const stableVersions = await v1.getVersions({
        filters: JSON.stringify([
          { field: "tags", operator: "contains", value: "stable" },
        ]),
      });

      expect(stableVersions.length).toBeGreaterThanOrEqual(1);
      const commits = stableVersions.map((v) => v.commit);
      expect(commits).toContain(v1.commit);
    }, 30000);

    it("should search versions by template content", async () => {
      const promptName = `test-search-template-${Date.now()}`;
      const searchTerm = "unique-search-term";

      // Create version 1 with search term
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: `This template contains ${searchTerm} for testing`,
      });
      createdPromptIds.push(v1.id);

      // Create version 2 without search term
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "This template has different content",
      });

      // Create version 3 with search term
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: `Another template with ${searchTerm} included`,
      });

      // Small delay to ensure backend indexing
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Search for versions containing the search term
      const searchResults = await v1.getVersions({
        search: searchTerm,
      });

      // Should return v1 and v3
      expect(searchResults.length).toBeGreaterThanOrEqual(2);
      const commits = searchResults.map((v) => v.commit);
      expect(commits).toContain(v1.commit);
      expect(commits).toContain(v3.commit);
      expect(commits).not.toContain(v2.commit);
    }, 30000);

    it("should search versions across multiple versions", async () => {
      const promptName = `test-search-multi-${Date.now()}`;
      const searchTerm = "search-keyword-xyz";

      // Create version 1 with search term
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: `Template with ${searchTerm} in v1`,
      });
      createdPromptIds.push(v1.id);

      // Create version 2 without the search term
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Template without keyword in v2",
      });

      // Create version 3 with the search term
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: `Template with ${searchTerm} in v3`,
      });

      // Small delay to ensure backend indexing
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Search for versions with the search term
      const searchResults = await v1.getVersions({
        search: searchTerm,
      });

      // Should find at least the versions with matching content
      expect(searchResults.length).toBeGreaterThanOrEqual(2);
      const commits = searchResults.map((v) => v.commit);
      expect(commits).toContain(v1.commit);
      expect(commits).toContain(v3.commit);
    }, 30000);

    it("should sort versions by created_at in descending order", async () => {
      const promptName = `test-sort-desc-${Date.now()}`;

      // Create versions with small delays to ensure different timestamps
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1",
      });
      createdPromptIds.push(v1.id);

      await new Promise((resolve) => setTimeout(resolve, 100));

      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Version 2",
      });

      await new Promise((resolve) => setTimeout(resolve, 100));

      const v3 = await client.createPrompt({
        name: promptName,
        prompt: "Version 3",
      });

      // Sort by created_at descending (newest first)
      const sortedVersions = await v1.getVersions({
        sorting: JSON.stringify([
          { field: "created_at", direction: "DESC" },
        ]),
      });

      expect(sortedVersions.length).toBe(3);
      // v3 should be first (newest), v1 should be last (oldest)
      expect(sortedVersions[0].commit).toBe(v3.commit);
      expect(sortedVersions[2].commit).toBe(v1.commit);
    }, 30000);

    it("should sort versions by created_at in ascending order", async () => {
      const promptName = `test-sort-asc-${Date.now()}`;

      // Create versions with small delays
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1",
      });
      createdPromptIds.push(v1.id);

      await new Promise((resolve) => setTimeout(resolve, 100));

      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Version 2",
      });

      await new Promise((resolve) => setTimeout(resolve, 100));

      const v3 = await client.createPrompt({
        name: promptName,
        prompt: "Version 3",
      });

      // Sort by created_at ascending (oldest first)
      const sortedVersions = await v1.getVersions({
        sorting: JSON.stringify([
          { field: "created_at", direction: "ASC" },
        ]),
      });

      expect(sortedVersions.length).toBe(3);
      // v1 should be first (oldest), v3 should be last (newest)
      expect(sortedVersions[0].commit).toBe(v1.commit);
      expect(sortedVersions[2].commit).toBe(v3.commit);
    }, 30000);

    it("should combine filtering, sorting, and searching", async () => {
      const promptName = `test-combined-${Date.now()}`;
      const searchTerm = "advanced-feature";

      // Create v1 with search term and container tag
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: `Template with ${searchTerm}`,
        tags: ["production"], // Container-level only
      });
      createdPromptIds.push(v1.id);
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v1.versionId], {
        tags: ["production"],
      });

      await new Promise((resolve) => setTimeout(resolve, 100));

      // Create v2 without search term but with container tag
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Different template",
        tags: ["production"], // Container-level only
      });
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v2.versionId], {
        tags: ["production"],
      });

      await new Promise((resolve) => setTimeout(resolve, 100));

      // Create v3 with search term and container tag
      const v3 = await client.createPrompt({
        name: promptName,
        prompt: `Another template with ${searchTerm}`,
        tags: ["production"], // Container-level only
      });
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v3.versionId], {
        tags: ["production"],
      });

      await new Promise((resolve) => setTimeout(resolve, 100));

      // Create v4 with search term but experimental container tag
      const v4 = await client.createPrompt({
        name: promptName,
        prompt: `Template with ${searchTerm} in experimental`,
        tags: ["experimental"], // Container-level only
      });
      // Explicitly set version-level tags
      await client.updatePromptVersionTags([v4.versionId], {
        tags: ["experimental"],
      });

      // Small delay to ensure backend indexing of version tags
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Search for "advanced-feature", filter by "production" tag (version-level), sort descending
      const results = await v1.getVersions({
        search: searchTerm,
        filters: JSON.stringify([
          { field: "tags", operator: "contains", value: "production" },
        ]),
        sorting: JSON.stringify([
          { field: "created_at", direction: "DESC" },
        ]),
      });

      // Should return v3 and v1 (both have search term and production tag), with v3 first
      expect(results.length).toBeGreaterThanOrEqual(2);
      expect(results[0].commit).toBe(v3.commit); // Newest
      expect(results[1].commit).toBe(v1.commit); // Oldest

      // v2 excluded (no search term), v4 excluded (no production tag)
      const commits = results.map((v) => v.commit);
      expect(commits).not.toContain(v2.commit);
      expect(commits).not.toContain(v4.commit);
    }, 30000);

    it("should return empty array when filter matches no versions", async () => {
      const promptName = `test-no-match-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Some template",
        tags: ["production"],
      });
      createdPromptIds.push(prompt.id);

      // Filter for a tag that doesn't exist
      const results = await prompt.getVersions({
        filters: JSON.stringify([
          { field: "tags", operator: "contains", value: "nonexistent-tag" },
        ]),
      });

      expect(results).toEqual([]);
    }, 30000);

    it("should return empty array when search matches no versions", async () => {
      const promptName = `test-search-no-match-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Template without special terms",
      });
      createdPromptIds.push(prompt.id);

      // Search for text that doesn't exist
      const results = await prompt.getVersions({
        search: "nonexistent-search-term-12345",
      });

      expect(results).toEqual([]);
    }, 30000);
  });
});

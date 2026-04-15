/**
 * Integration tests for OpikClient evaluation suite CRUD methods.
 * These tests exercise client.createTestSuite / getTestSuite /
 * getOrCreateTestSuite / deleteTestSuite / getTestSuites,
 * which delegate to TestSuite static methods via a dynamic import.
 * They act as a regression guard for the circular-dependency fix introduced
 * in Client.ts (import type + await import()).
 *
 * Happy-path only — edge cases are covered in the TestSuite unit tests.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik, TestSuite } from "@/index";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();
const WAIT_OPTIONS = { timeout: 15000, interval: 1000 };

describe.skipIf(!shouldRunApiTests)(
  "OpikClient TestSuite CRUD Integration",
  () => {
    let client: Opik;
    const createdSuiteNames: string[] = [];

    beforeAll(() => {
      console.log(getIntegrationTestStatus());

      if (!shouldRunApiTests) {
        return;
      }

      client = new Opik();
    });

    afterAll(async () => {
      if (!client) return;

      for (const name of createdSuiteNames) {
        try {
          await client.deleteTestSuite(name);
        } catch {
          // ignore cleanup errors
        }
      }

      await client.flush();
    });

    it(
      "createTestSuite — returns a TestSuite with correct name and id",
      async () => {
        const name = `client-suite-create-${Date.now()}`;
        createdSuiteNames.push(name);

        const suite = await client.createTestSuite({ name });

        expect(suite).toBeInstanceOf(TestSuite);
        expect(suite.name).toBe(name);
        expect(suite.id).toBeDefined();
      },
      30000
    );

    it(
      "getTestSuite — retrieves a previously created suite by name",
      async () => {
        const name = `client-suite-get-${Date.now()}`;
        createdSuiteNames.push(name);

        const created = await client.createTestSuite({ name });
        const fetched = await client.getTestSuite(name);

        expect(fetched).toBeInstanceOf(TestSuite);
        expect(fetched.name).toBe(name);
        expect(fetched.id).toBe(created.id);
      },
      30000
    );

    it(
      "getOrCreateTestSuite — creates when absent, returns same suite when called again",
      async () => {
        const name = `client-suite-getorcreate-${Date.now()}`;
        createdSuiteNames.push(name);

        const first = await client.getOrCreateTestSuite({ name });
        expect(first).toBeInstanceOf(TestSuite);
        expect(first.name).toBe(name);

        const second = await client.getOrCreateTestSuite({ name });
        expect(second.id).toBe(first.id);
      },
      30000
    );

    it(
      "deleteTestSuite — removes the suite so a subsequent get throws",
      async () => {
        const name = `client-suite-delete-${Date.now()}`;
        // not pushed to createdSuiteNames because we delete it in the test
        await client.createTestSuite({ name });

        await client.deleteTestSuite(name);

        await expect(client.getTestSuite(name)).rejects.toThrow();
      },
      30000
    );

    it(
      "getTestSuites — returns a list that includes a created suite",
      async () => {
        const name = `client-suite-list-${Date.now()}`;
        createdSuiteNames.push(name);

        await client.createTestSuite({ name });

        const matched = await searchAndWaitForDone(
          async () => {
            const all = await client.getTestSuites(200);
            return all.filter((s) => s.name === name);
          },
          1,
          WAIT_OPTIONS.timeout,
          WAIT_OPTIONS.interval
        );

        expect(matched.length).toBeGreaterThanOrEqual(1);
        expect(matched[0]).toBeInstanceOf(TestSuite);
        expect(matched[0].name).toBe(name);
      },
      30000
    );
  }
);
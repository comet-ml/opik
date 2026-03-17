import { Dataset } from "@/dataset/Dataset";
import { DatasetItemData, DatasetNotFoundError } from "@/dataset";
import { OpikClient } from "@/client/Client";
import {
  resolveEvaluators,
  validateExecutionPolicy,
} from "../suite_evaluators/validators";
import type {
  EvaluationSuiteResult,
  EvaluationSuiteItem,
  ExecutionPolicy,
} from "./types";
import { buildSuiteResult } from "./suiteResultConstructor";
import { evaluateSuite } from "./evaluateSuite";
import {
  serializeEvaluators,
  deserializeEvaluators,
  resolveExecutionPolicy,
  resolveItemExecutionPolicy,
} from "./suiteHelpers";
import type { EvaluatorItemLike } from "./suiteHelpers";
import type { EvaluationTask } from "../types";
import { DatasetWriteType } from "@/rest_api/api/resources/datasets/types/DatasetWriteType";
import type { Prompt } from "@/prompt/Prompt";
import { generateId } from "@/utils/generateId";

export interface EvaluationSuiteRunOptions {
  experimentName?: string;
  projectName?: string;
  experimentConfig?: Record<string, unknown>;
  prompts?: Prompt[];
  experimentTags?: string[];
  model?: string;
}

export interface AddItemOptions {
  assertions?: string[];
  description?: string;
  executionPolicy?: ExecutionPolicy;
}

export interface CreateEvaluationSuiteOptions {
  name: string;
  description?: string;
  assertions?: string[];
  executionPolicy?: ExecutionPolicy;
  tags?: string[];
}

function validateSuiteName(name: string): void {
  if (!name || name.trim() === "") {
    throw new Error("Evaluation suite name must be a non-empty string");
  }
}

function extractAssertions(evaluators: EvaluatorItemLike[] | undefined): string[] {
  if (!evaluators) return [];
  const judges = deserializeEvaluators(evaluators);
  return judges.flatMap((judge) => judge.assertions);
}

function prepareDatasetItemData(
  data: Record<string, unknown>,
  options?: { assertions?: string[]; description?: string; executionPolicy?: ExecutionPolicy }
): DatasetItemData {
  if (options?.executionPolicy) {
    validateExecutionPolicy(options.executionPolicy, "item-level execution policy");
  }

  const resolvedEvaluators = resolveEvaluators(
    options?.assertions,
    undefined,
    "item-level assertions"
  );

  const evaluators = resolvedEvaluators
    ? serializeEvaluators(resolvedEvaluators)
    : undefined;

  return {
    ...data,
    ...(options?.description && { description: options.description }),
    ...(evaluators && { evaluators }),
    ...(options?.executionPolicy && { executionPolicy: options.executionPolicy }),
  };
}

function validateTaskResult(
  result: unknown
): Record<string, unknown> {
  if (typeof result !== "object" || result === null) {
    throw new TypeError(
      `The task function must return an object with 'input' and 'output' keys, ` +
        `but it returned ${typeof result}. ` +
        `Example: return { input: data, output: response }`
    );
  }
  const dict = result as Record<string, unknown>;
  const missing: string[] = [];
  if (!("input" in dict)) missing.push("input");
  if (!("output" in dict)) missing.push("output");
  if (missing.length > 0) {
    throw new Error(
      `The task function must return an object with 'input' and 'output' keys, ` +
        `but the returned object is missing: ${missing.join(", ")}. ` +
        `Got keys: ${Object.keys(dict).join(", ")}. ` +
        `Example: return { input: data, output: response }`
    );
  }
  return dict;
}

export class EvaluationSuite {
  public readonly name: string;
  public readonly description?: string;

  constructor(
    private readonly dataset: Dataset,
    private readonly client: OpikClient
  ) {
    this.name = dataset.name;
    this.description = dataset.description;
  }

  get id(): string {
    return this.dataset.id;
  }

  // ---------------------------------------------------------------------------
  // Static factory methods (replace Client.ts methods — no circular dep)
  // ---------------------------------------------------------------------------

  static async create(
    client: OpikClient,
    options: CreateEvaluationSuiteOptions
  ): Promise<EvaluationSuite> {
    validateSuiteName(options.name);

    const resolvedEvaluators = resolveEvaluators(
      options.assertions,
      undefined,
      "suite-level assertions"
    );

    if (options.executionPolicy) {
      validateExecutionPolicy(options.executionPolicy, "suite creation");
    }

    const datasetId = generateId();
    await client.api.datasets.createDataset({
      id: datasetId,
      name: options.name,
      description: options.description,
      type: DatasetWriteType.EvaluationSuite,
      tags: options.tags,
    });

    const dataset = new Dataset(
      { id: datasetId, name: options.name, description: options.description },
      client
    );

    if (resolvedEvaluators || options.executionPolicy) {
      const evaluators = resolvedEvaluators
        ? serializeEvaluators(resolvedEvaluators)
        : undefined;

      await client.api.datasets.applyDatasetItemChanges(datasetId, {
        override: true,
        body: {
          ...(evaluators && { evaluators }),
          ...(options.executionPolicy && {
            execution_policy: {
              runs_per_item: options.executionPolicy.runsPerItem,
              pass_threshold: options.executionPolicy.passThreshold,
            },
          }),
        },
      });
    }

    return new EvaluationSuite(dataset, client);
  }

  static async get(
    client: OpikClient,
    name: string
  ): Promise<EvaluationSuite> {
    const dataset = await client.getDataset(name);
    await dataset.syncHashes();
    return new EvaluationSuite(dataset, client);
  }

  static async getOrCreate(
    client: OpikClient,
    options: CreateEvaluationSuiteOptions
  ): Promise<EvaluationSuite> {
    validateSuiteName(options.name);

    try {
      const suite = await EvaluationSuite.get(client, options.name);

      const hasUpdates =
        options.assertions !== undefined ||
        options.executionPolicy !== undefined ||
        options.tags !== undefined;

      if (hasUpdates) {
        await suite.update({
          assertions: options.assertions,
          executionPolicy: options.executionPolicy,
          tags: options.tags,
        });
      }

      return suite;
    } catch (error) {
      if (error instanceof DatasetNotFoundError) {
        return EvaluationSuite.create(client, options);
      }
      throw error;
    }
  }

  // ---------------------------------------------------------------------------
  // Instance methods
  // ---------------------------------------------------------------------------

  async addItem(
    data: Record<string, unknown>,
    options?: AddItemOptions
  ): Promise<void> {
    await this.dataset.insert([prepareDatasetItemData(data, options)]);
  }

  async addItems(items: EvaluationSuiteItem[]): Promise<void> {
    const datasetItems: DatasetItemData[] = items.map((item) =>
      prepareDatasetItemData(item.data, item)
    );

    await this.dataset.insert(datasetItems);
  }

  async run(
    task: EvaluationTask,
    options?: EvaluationSuiteRunOptions
  ): Promise<EvaluationSuiteResult> {
    const validatedTask: EvaluationTask = async (item) => {
      const result = await task(item);
      return validateTaskResult(result);
    };

    const evalResult = await evaluateSuite({
      dataset: this.dataset,
      task: validatedTask,
      experimentName: options?.experimentName,
      projectName: options?.projectName,
      experimentConfig: options?.experimentConfig,
      prompts: options?.prompts,
      evaluatorModel: options?.model,
      tags: options?.experimentTags,
      client: this.client,
    });

    return buildSuiteResult(evalResult);
  }

  async getItems(): Promise<
    Array<{
      id: string;
      data: Record<string, unknown>;
      description?: string;
      assertions: string[];
      executionPolicy: Required<ExecutionPolicy>;
    }>
  > {
    const rawItems = await this.dataset.getRawItems();
    const suitePolicy = await this.getExecutionPolicy();

    return rawItems.map((item) => {
      const { id, ...data } = item.getContent(true);
      return {
        id: id ?? "",
        data,
        description: item.description,
        assertions: extractAssertions(item.evaluators),
        executionPolicy: resolveItemExecutionPolicy(
          item.executionPolicy,
          suitePolicy
        ),
      };
    });
  }

  async getAssertions(): Promise<string[]> {
    const versionInfo = await this.dataset.getVersionInfo();
    return extractAssertions(versionInfo?.evaluators);
  }

  async getTags(): Promise<string[]> {
    return this.dataset.getTags();
  }

  async getExecutionPolicy(): Promise<Required<ExecutionPolicy>> {
    const versionInfo = await this.dataset.getVersionInfo();
    return resolveExecutionPolicy(versionInfo?.executionPolicy);
  }

  async update(options: {
    assertions?: string[];
    executionPolicy?: ExecutionPolicy;
    tags?: string[];
  }): Promise<void> {
    if (options.executionPolicy) {
      validateExecutionPolicy(options.executionPolicy, "suite update");
    }

    const resolvedEvaluators = resolveEvaluators(
      options.assertions,
      undefined,
      "suite-level assertions"
    );

    const assertionsProvided = options.assertions !== undefined;

    if (!resolvedEvaluators && !assertionsProvided && !options.executionPolicy && !options.tags) {
      throw new Error(
        "At least one of 'assertions', 'executionPolicy', or 'tags' must be provided."
      );
    }

    // Tags are dataset-level, updated separately
    if (options.tags) {
      await this.client.api.datasets.updateDataset(this.dataset.id, {
        name: this.name,
        tags: options.tags,
      });
    }

    const hasVersionUpdates = resolvedEvaluators || assertionsProvided || options.executionPolicy;
    if (hasVersionUpdates) {
      const versionInfo = await this.dataset.getVersionInfo();
      if (!versionInfo) {
        throw new Error(
          `Cannot update evaluation suite '${this.name}': ` +
            "no version info found. Add at least one item first."
        );
      }

      // Partial updates: retain current values for omitted params
      const evaluators = resolvedEvaluators ??
        (assertionsProvided
          ? []
          : (versionInfo.evaluators
            ? deserializeEvaluators(versionInfo.evaluators)
            : []));
      const executionPolicy = options.executionPolicy ??
        resolveExecutionPolicy(versionInfo.executionPolicy);

      await this.client.api.datasets.applyDatasetItemChanges(this.dataset.id, {
        override: false,
        body: {
          base_version: versionInfo.id,
          evaluators: serializeEvaluators(evaluators),
          execution_policy: {
            runs_per_item: executionPolicy.runsPerItem,
            pass_threshold: executionPolicy.passThreshold,
          },
        },
      });
    }
  }

  async deleteItems(itemIds: string[]): Promise<void> {
    await this.dataset.delete(itemIds);
  }
}

import type { OpikClient } from "@/client/Client";
import { OpikApiError } from "@/rest_api";
import * as OpikApi from "@/rest_api/api";
import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";
import { Blueprint } from "./Blueprint";

export interface CreateBlueprintOptions {
  values: Record<string, string>;
  description?: string;
}

export interface GetBlueprintOptions {
  id?: string;
  env?: string;
  maskId?: string;
}

export class AgentConfig {
  private readonly projectName: string;
  private readonly opik: OpikClient;

  constructor(projectName: string, opik: OpikClient) {
    this.projectName = projectName;
    this.opik = opik;
  }

  private async getProjectId(): Promise<string> {
    const project = await this.opik.api.projects.retrieveProject({
      name: this.projectName,
    });
    if (!project?.id) {
      throw new Error(`Project "${this.projectName}" not found`);
    }
    return project.id;
  }

  private async createBlueprintInternal(
    options: CreateBlueprintOptions,
    type: OpikApi.AgentBlueprintWriteType
  ): Promise<Blueprint> {
    const id = generateId();
    const values: OpikApi.AgentConfigValueWrite[] = Object.entries(
      options.values
    ).map(([key, value]) => ({
      key,
      value,
      type: OpikApi.AgentConfigValueWriteType.String,
    }));

    logger.debug(`Creating ${type} for project "${this.projectName}"`);

    await this.opik.api.agentConfigs.createAgentConfig({
      id,
      projectName: this.projectName,
      blueprint: {
        id,
        type,
        description: options.description,
        values,
      },
    });

    const response = await this.opik.api.agentConfigs.getBlueprintById(id);
    return Blueprint.fromApiResponse(response);
  }

  /**
   * Creates a new blueprint for the project and returns it.
   *
   * A blueprint is a versioned snapshot of config key/value pairs. Each call
   * creates a new version; use `getBlueprint()` to retrieve the latest.
   */
  async createBlueprint(options: CreateBlueprintOptions): Promise<Blueprint> {
    return this.createBlueprintInternal(
      options,
      OpikApi.AgentBlueprintWriteType.Blueprint
    );
  }

  /**
   * Creates a mask — a partial override of config values used for A/B testing
   * or feature flags. Returns the mask ID.
   *
   * Pass the returned mask ID to `getBlueprint({ maskId })` to retrieve a
   * blueprint with the mask's values overlaid on top of the base blueprint.
   */
  async createMask(options: CreateBlueprintOptions): Promise<string> {
    const id = generateId();
    const values: OpikApi.AgentConfigValueWrite[] = Object.entries(
      options.values
    ).map(([key, value]) => ({
      key,
      value,
      type: OpikApi.AgentConfigValueWriteType.String,
    }));

    logger.debug(`Creating mask for project "${this.projectName}"`);

    await this.opik.api.agentConfigs.createAgentConfig({
      id,
      projectName: this.projectName,
      blueprint: {
        id,
        type: OpikApi.AgentBlueprintWriteType.Mask,
        description: options.description,
        values,
      },
    });

    return id;
  }

  /**
   * Retrieves a blueprint. Returns `null` if none is found (no error thrown).
   *
   * Resolution order:
   * - `id` — fetches the blueprint with that exact ID.
   * - `env` — fetches the blueprint pinned to that environment label.
   * - neither — fetches the latest blueprint for the project.
   *
   * Pass `maskId` to overlay a mask's values on top of the resolved blueprint.
   */
  async getBlueprint(
    options: GetBlueprintOptions = {}
  ): Promise<Blueprint | null> {
    const { id, env, maskId } = options;

    try {
      let response: OpikApi.AgentBlueprintPublic;

      if (id) {
        logger.debug(
          `Getting blueprint by ID "${id}" for project "${this.projectName}"`
        );
        response = await this.opik.api.agentConfigs.getBlueprintById(id, {
          maskId,
        });
      } else if (env) {
        const projectId = await this.getProjectId();
        logger.debug(
          `Getting blueprint by env "${env}" for project "${this.projectName}"`
        );
        response = await this.opik.api.agentConfigs.getBlueprintByEnv(
          env,
          projectId,
          { maskId }
        );
      } else {
        const projectId = await this.getProjectId();
        logger.debug(`Getting latest blueprint for project "${this.projectName}"`);
        response = await this.opik.api.agentConfigs.getLatestBlueprint(
          projectId,
          { maskId }
        );
      }

      return Blueprint.fromApiResponse(response);
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        return null;
      }
      logger.error(`Failed to get blueprint for project "${this.projectName}"`, {
        error,
      });
      throw error;
    }
  }

  /**
   * Associates a blueprint with an environment label (e.g. `"prod"`, `"staging"`).
   * After tagging, `getBlueprint({ env })` will return this blueprint.
   */
  async tagBlueprintWithEnv(
    blueprintId: string,
    env: string
  ): Promise<void> {
    const projectId = await this.getProjectId();
    logger.debug(
      `Tagging blueprint "${blueprintId}" with env "${env}" for project "${this.projectName}"`
    );
    await this.opik.api.agentConfigs.createOrUpdateEnvs({
      projectId,
      envs: [{ envName: env, blueprintId }],
    });
  }
}

/**
 * Dataset class for managing datasets and their items.
 * Provides methods for creating, retrieving, and managing datasets and their items.
 */

import { generateId } from "@/utils/generateId";

export class Dataset {
  public readonly id: string;
  public readonly name: string;
  public readonly description?: string;

  /**
   * Creates a new Dataset instance.
   * This should not be created directly, use static factory methods instead.
   *
   * @param name The name of the dataset
   * @param description Optional description of the dataset
   * @param id Optional dataset ID if already known
   */
  constructor(name: string, description?: string, id?: string) {
    this.id = id || generateId();
    this.name = name;
    this.description = description;
  }
}

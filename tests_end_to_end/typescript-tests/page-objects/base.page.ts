/**
 * Base Page Object
 *
 * Mirrors the Python BasePage class from page_objects/BasePage.py
 * Handles workspace-aware navigation for all pages
 */

import { Page } from '@playwright/test';
import { getEnvironmentConfig } from '../config/env.config';

export class BasePage {
  protected page: Page;
  protected workspace: string;
  protected baseUrl: string;
  protected path: string;

  constructor(page: Page, path: string, queryParams: string = '') {
    this.page = page;

    const envConfig = getEnvironmentConfig();
    const envData = envConfig.getConfig();

    this.workspace = envData.workspace;
    this.baseUrl = envData.baseUrl;

    // Remove leading/trailing slashes and combine path components
    const cleanPath = path.replace(/^\/+|\/+$/g, '');
    this.path = `${this.workspace}/${cleanPath}`;

    // Add query params if provided
    if (queryParams) {
      if (!queryParams.startsWith('?')) {
        queryParams = `?${queryParams}`;
      }
      this.path = `${this.path}${queryParams}`;
    }
  }

  /**
   * Navigate to the page URL
   */
  async goto(): Promise<void> {
    const fullUrl = `${this.baseUrl.replace(/\/+$/, '')}/${this.path}`;
    await this.page.goto(fullUrl);
  }

  /**
   * Get the full URL for this page
   */
  getUrl(): string {
    return `${this.baseUrl.replace(/\/+$/, '')}/${this.path}`;
  }
}

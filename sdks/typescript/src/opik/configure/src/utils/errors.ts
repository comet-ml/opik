export class RateLimitError extends Error {
  constructor() {
    super('CLI usage limit reached.');
    this.name = 'RateLimitError';
  }
}

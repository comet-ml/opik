export class RateLimitError extends Error {
  constructor() {
    super('Wizard usage limit reached.');
    this.name = 'RateLimitError';
  }
}

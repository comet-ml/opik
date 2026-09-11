/**
 * Utility functions for masking sensitive information in terminal output
 */

/**
 * Masks an API key for safe display in terminal output
 * Shows first 4 and last 4 characters, masks the middle with "..."
 *
 * @param apiKey - The API key to mask (can be undefined or null)
 * @returns Masked API key in format "abcd...wxyz" or "(empty)" if no key provided
 *
 * @example
 * maskApiKey('abcdefghijklmnop') // Returns: 'abcd...mnop'
 * maskApiKey('short') // Returns: 'short' (keys <= 8 chars are not masked)
 * maskApiKey('') // Returns: '(empty)'
 * maskApiKey(undefined) // Returns: '(empty)'
 */
export function maskApiKey(apiKey: string | undefined | null): string {
  if (!apiKey) return '(empty)';
  if (apiKey.length <= 8) return apiKey;
  return `${apiKey.substring(0, 4)}...${apiKey.substring(apiKey.length - 4)}`;
}

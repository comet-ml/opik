/**
 * Low-level tokenizer for character-by-character parsing
 */

/**
 * QueryTokenizer handles character-level operations for parsing OQL queries
 */
export class QueryTokenizer {
  private cursor: number = 0;

  constructor(private readonly input: string) {}

  /**
   * Returns current position in the input string
   */
  getPosition(): number {
    return this.cursor;
  }

  /**
   * Returns remaining unprocessed input
   */
  getRemainingInput(): string {
    return this.input.slice(this.cursor);
  }

  /**
   * Returns the full input string
   */
  getFullInput(): string {
    return this.input;
  }

  /**
   * Checks if we've reached the end of input
   */
  isAtEnd(): boolean {
    return this.cursor >= this.input.length;
  }

  /**
   * Returns current character without advancing
   */
  peekChar(): string {
    return this.input[this.cursor];
  }

  /**
   * Returns character at offset without advancing
   */
  peekCharAt(offset: number): string {
    return this.input[this.cursor + offset];
  }

  /**
   * Advances cursor and returns the consumed character
   */
  consumeChar(): string {
    return this.input[this.cursor++];
  }

  /**
   * Advances cursor by n positions
   */
  advance(n: number = 1): void {
    this.cursor += n;
  }

  /**
   * Skips whitespace characters
   */
  skipWhitespace(): void {
    while (
      this.cursor < this.input.length &&
      /\s/.test(this.input[this.cursor])
    ) {
      this.cursor++;
    }
  }

  /**
   * Consumes characters while predicate is true
   */
  consumeWhile(predicate: (char: string) => boolean): string {
    const start = this.cursor;
    while (
      this.cursor < this.input.length &&
      predicate(this.input[this.cursor])
    ) {
      this.cursor++;
    }
    return this.input.slice(start, this.cursor);
  }

  /**
   * Returns a slice of input from start to current position
   */
  sliceFrom(start: number): string {
    return this.input.slice(start, this.cursor);
  }

  /**
   * Checks if character is a valid field character (alphanumeric or underscore)
   */
  static isFieldChar(char: string): boolean {
    return /[a-zA-Z0-9_]/.test(char);
  }

  /**
   * Checks if character is a letter (for connectors like 'and', 'or')
   */
  static isLetterChar(char: string): boolean {
    return /[a-zA-Z]/.test(char);
  }

  /**
   * Checks if character is a digit
   */
  static isDigitChar(char: string): boolean {
    return /\d/.test(char);
  }

  /**
   * Checks if character is whitespace
   */
  static isWhitespaceChar(char: string): boolean {
    return /\s/.test(char);
  }

  /**
   * Checks if character is a quote (single or double)
   */
  static isQuoteChar(char: string): boolean {
    return char === '"' || char === "'";
  }
}

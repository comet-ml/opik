const DEFAULT_VISION_MODELS = [
  // OpenAI
  "gpt-4-vision",
  "gpt-4o",
  "gpt-4o-mini",
  "gpt-4-turbo",
  "chatgpt-4o-latest",
  "gpt-5-mini",
  // Anthropic
  "claude-3",
  "claude-3-5",
  // Google
  "gemini-1.5-pro",
  "gemini-1.5-flash",
  "gemini-pro-vision",
  "gemini-2.0-flash",
  // Meta
  "llama-3.2-11b-vision",
  "llama-3.2-90b-vision",
  // Mistral
  "pixtral",
  // Misc
  "qwen-vl",
  "qwen2-vl",
  "phi-3-vision",
  "phi-3.5-vision",
  "llava",
  "cogvlm",
  "yi-vl",
];

export class ModelCapabilities {
  private static visionModels = new Set(
    DEFAULT_VISION_MODELS.map((model) => model.toLowerCase())
  );

  static supportsVision(modelName?: string | null): boolean {
    if (!modelName) {
      return false;
    }

    const normalized = modelName.toLowerCase();
    for (const prefix of this.visionModels) {
      if (normalized.startsWith(prefix)) {
        return true;
      }
    }

    return false;
  }

  static addVisionModel(modelName: string): void {
    if (modelName) {
      this.visionModels.add(modelName.toLowerCase());
    }
  }
}

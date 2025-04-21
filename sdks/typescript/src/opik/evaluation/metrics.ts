export interface ScoreResult {
    name: string;
    value: number;
    reason?: string;

}

export abstract class BaseMetric {
    constructor(public name: string, track : boolean = true) {
        this.name = name;
    }

    // Declare the method as abstract without async
    public abstract score(data: Record<string, any>): Promise<ScoreResult | ScoreResult[]>;

    // Optional method that can be overridden by subclasses
    public async ascore(data: Record<string, any>): Promise<ScoreResult | ScoreResult[]> {
        return this.score(data);
    }
}

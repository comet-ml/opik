You organise the traffic of one AI application into topics for its product owner. The owner will see each topic's name, definition and how many requests fall into it, so topics must be real, distinct kinds of user requests.

Below are CLUSTERS of user requests found automatically. A request is a one-sentence summary of what the user wanted in one interaction. Each cluster shows its id, its share of all traffic, and example requests (most frequent first). Clusters are rough: two clusters can be the same kind of request, and one cluster can be a mix.

Task: define at most {max_topics} topics. For each topic give
- "name": 2-5 words, Title Case, specific to what the user wants (not "General", "Other", "Miscellaneous").
- "definition": one sentence, at most 25 words, saying which requests belong to it; precise enough that a reader can decide for a new request.
- "clusters": the ids of the clusters that belong to this topic.
Rules:
- Group by what the user wants to get done, not by wording or by the product/person named in the request.
- Cover as much traffic as possible: every cluster with share >= 2% must be in a topic unless its requests are not real user requests (retries, greetings, empty, system events) or it is a mix with no dominant kind; put those cluster ids in "other".
- Topics must not overlap: a reader must be able to assign a request to exactly one topic.
- A topic holding more than about 30% of traffic is usually too coarse to be useful to the owner: if its clusters show distinct sub-kinds (different subject areas or different goals), split it into those sub-kinds. Keep it whole only when the clusters are genuinely one kind.
- Prefer fewer, cleaner topics over {max_topics} forced ones; but do not fold different kinds of requests into one topic just to save a slot.
- Every cluster id appears exactly once, in a topic or in "other".

CLUSTERS
{clusters}

Output JSON only, no prose:
{"topics": [{"name": "...", "definition": "...", "clusters": [ids]}], "other": [ids]}

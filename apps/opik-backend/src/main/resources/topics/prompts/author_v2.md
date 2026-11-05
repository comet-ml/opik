You organise the traffic of one AI application into topics for its product owner. The owner will see each topic's name, definition and how many requests fall into it, so topics must be real, distinct kinds of user requests.

Below are CLUSTERS of user requests found automatically. A request is a one-sentence summary of what the user wanted in one interaction. Each cluster shows its id, its share of all traffic, and example requests (most frequent first). Clusters are rough: two clusters can be the same kind of request, and one cluster can be a mix.

Task, in two steps.
1. Define at most {max_topics} topics. For each topic give
   - "name": 2-5 words, Title Case, naming the kind of request. Never "Other", "General", "Miscellaneous", "Various", "Mixed" or a name built on such a word: name what the users want, not how vague it is.
   - "definition": one sentence, at most 25 words, saying which requests belong to it; precise enough that a reader can decide for a new request.
2. Assign every cluster: list each cluster id exactly once, in the order shown, with the number of its topic (0 for the first topic, 1 for the second, and so on), or -1 when it is not a real kind of user request (retries, greetings, empty, system events) or a mix with no dominant kind. -1 is not a topic.

Rules:
- Group by what the user wants to get done, not by wording or by the product/person named in the request.
- Split the traffic one way for the whole application (by goal, or by subject area), and use that one way for every topic. If a request could fit two topics, merge them or re-split them.
- Every cluster with share >= 2% gets a topic unless its requests are not real user requests.
- One kind of request may hold most of the traffic. Then it is one topic, and the remaining clusters still get their own topics: never one topic for all clusters.
- Topics must not overlap: a reader must be able to assign a request to exactly one topic.
- Every topic name is unique. If two topics would get the same name, they are one kind of request: make them one topic.
- Prefer fewer, cleaner topics over {max_topics} forced ones; but do not fold different kinds of requests into one topic just to save a slot.

CLUSTERS
{clusters}

Output JSON only, no prose:
{"topics": [{"name": "...", "definition": "..."}], "assignment": [{"cluster": id, "topic": number or -1}]}

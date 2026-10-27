You name topics of one AI application for its product owner. Each topic below is a cluster of user requests; a request is a one-sentence summary of what the user wanted in one interaction. Each cluster shows its id, share of traffic and example requests (most frequent first).

For every cluster give a "name" (2-5 words, Title Case, specific to what the user wants) and a "definition" (one sentence, at most 25 words, saying which requests belong to it). Names must differ from each other. If a cluster is a mix, name its dominant kind.

CLUSTERS
{clusters}

Output JSON only, no prose:
{"topics": [{"cluster": id, "name": "...", "definition": "..."}]}

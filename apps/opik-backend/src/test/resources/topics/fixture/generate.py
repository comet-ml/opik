"""Regenerates the expected outputs of the Topics fixture from its inputs.

Inputs, in this folder:
  window.json      the window: per trace its id, created_at and intent text (synthetic)
  window.f32       the trace vectors as the backend reads them: little-endian float32, 384 per trace
  answer.json      a recorded author answer, with `groups`, the grouping its cluster ids refer to (text -> cluster)
  java_kmeans.json the backend's k-means grouping of the window (KMeans.fit, seed 0), per unique text

Outputs:
  centres.f32      scikit-learn's unit centres for the window (seed 0)
  expected.json    each step of the first build: merge, scikit-learn k-means, group summaries, the answer and its
                   application, prototypes, final labels; `first_build` the same steps on the backend's grouping
  first_build_centres.f32  the unit centres of the backend's grouping

The rules follow the backend: IntentMerger, GroupSummaries, AuthorAnswer, AuthorApplier, PrototypeAssigner. The
backend's k-means is its own, so this script reads its grouping and checks it only by the quality bar in
TopicsAlgorithmFixtureTest. An answer is moved onto another grouping cluster by cluster: a group goes to the answer
cluster that holds most of its traces (ties to the lower id).

Usage (numpy and scikit-learn):
  python generate.py                       regenerate the outputs
  python generate.py --bf16-from RAW.f32   first write window.f32 from float32 embeddings as the backend reads them
                                           from ClickHouse: truncated to BFloat16, then scaled back to unit length
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
from sklearn.cluster import KMeans
from sklearn.metrics import adjusted_rand_score

HERE = Path(__file__).resolve().parent
DIMENSIONS = 384
K = 15  # the fixture's grouping; the backend's first build uses finer groups on real windows
SEEDS = 10
RECENT_DAYS = 7
MAX_TOPICS = 10
ASSIGN_MIN_COSINE = 0.75
OTHER_GATE = 0.95
SUMMARY_FREQUENT, SUMMARY_EXTRA = 3, 2
CATCH_ALL_NAME = re.compile(
    r"(other|others|misc|miscellaneous|general|various|mixed)"
    r"( (requests|queries|questions|inquiries|tasks|topics|items|things|stuff))?")


def bf16_as_read(raw: np.ndarray) -> np.ndarray:
    """A vector as the backend reads it back: ClickHouse truncates it to BFloat16, the read scales it to unit length
    (norm in double, the scale in float)."""
    truncated = (raw.astype("<f4").view("<u4") & np.uint32(0xFFFF0000)).view("<f4")
    out = np.empty_like(truncated)
    for i, vector in enumerate(truncated):
        norm = float(np.dot(vector.astype(np.float64), vector.astype(np.float64)))
        scale = np.float32(1 / np.sqrt(norm)) if norm > 0 else np.float32(1)
        out[i] = vector * scale
    return out


def merge(intents: list[str]):
    """IntentMerger: unique texts in code point order, their trace counts, first traces and each trace's text."""
    unique, inverse, counts = np.unique(np.asarray(intents, dtype=object).astype(str), return_inverse=True,
                                        return_counts=True)
    first = np.zeros(len(unique), dtype=int)
    first[inverse[::-1]] = np.arange(len(inverse))[::-1]
    return [str(u) for u in unique], inverse, counts, first


def unit_centres(vectors: np.ndarray, weights: np.ndarray, labels: np.ndarray, k: int) -> np.ndarray:
    centres = np.zeros((k, vectors.shape[1]))
    np.add.at(centres, labels, vectors.astype(np.float64) * weights[:, None])
    return (centres / np.linalg.norm(centres, axis=1, keepdims=True)).astype(np.float32)


def examples(texts, x, labels, centres, frequent, extra):
    """GroupSummaries.authorExamples per group: texts seen at least twice in the group (most first, ties by code
    point), at most `frequent`, then the texts nearest the centre (ties by trace order) up to frequent + extra."""
    x64, c64 = x.astype(np.float64), centres.astype(np.float64)
    out = []
    for j in range(len(centres)):
        idx = np.where(labels == j)[0]
        if len(idx) == 0:
            out.append([])
            continue
        values, counts = np.unique(np.asarray([texts[i] for i in idx], dtype=object).astype(str), return_counts=True)
        order = np.argsort(-counts, kind="stable")
        values, counts = [str(v) for v in values[order]], counts[order]
        picked = values[:min(frequent, int((counts >= 2).sum()))]
        seen = set(picked)
        for o in np.argsort(-(x64[idx] @ c64[j]), kind="stable"):
            if len(picked) >= frequent + extra:
                break
            text = texts[idx[o]]
            if text not in seen:
                picked.append(text)
                seen.add(text)
        out.append(picked)
    return out


def summaries(intents, x, labels, centres, recent):
    n = len(labels)
    x64, c64 = x.astype(np.float64), centres.astype(np.float64)
    picked = examples(intents, x, labels, centres, SUMMARY_FREQUENT, SUMMARY_EXTRA)
    out = []
    for j in range(len(centres)):
        idx = np.where(labels == j)[0]
        if len(idx) == 0:
            out.append({"cluster": j, "size": 0, "share": 0.0, "recent_share": 0.0, "coherence": 0.0, "examples": [],
                        "n_unique": 0})
            continue
        out.append({"cluster": j, "size": int(len(idx)), "share": float(len(idx) / n),
                    "recent_share": float(recent[idx].sum() / max(1, recent.sum())),
                    "coherence": float((x64[idx] @ c64[j]).mean()), "examples": picked[j],
                    "n_unique": int(len(set(intents[i] for i in idx)))})
    return out


def answer_on(answer: dict, unique: list[str], counts: np.ndarray, groups: np.ndarray, k: int) -> dict:
    """The answer moved onto `groups`: each group takes the topic of the answer cluster holding most of its traces,
    in that cluster's place in the answer (so the answer's own grouping gets the answer back unchanged)."""
    answer_cluster = np.asarray([answer["groups"][text] for text in unique])
    place = {c: (t, i) for t, topic in enumerate(answer["topics"]) for i, c in enumerate(topic["clusters"])}
    place |= {c: (-1, i) for i, c in enumerate(answer["other"])}
    moved = []
    for g in range(k):
        members = groups == g
        source = int(np.bincount(answer_cluster[members], weights=counts[members]).argmax()) if members.any() else -1
        moved.append((*place.get(source, (-1, len(answer["other"]))), g))
    moved.sort(key=lambda item: item[1:])
    return {"topics": [{"name": topic["name"], "definition": topic["definition"],
                        "clusters": [g for t2, _, g in moved if t2 == t]} for t, topic in enumerate(answer["topics"])],
            "other": [g for t, _, g in moved if t == -1]}


def name_key(name: str) -> str:
    return " ".join(name.split()).lower()


def apply_author(answer: dict, sizes: list[int]):
    """AuthorApplier.apply on the answer's per-cluster form (topic clusters in order, then Other)."""
    k = len(sizes)

    def valid_size(topic):
        return sum(sizes[j] for j in topic["clusters"] if 0 <= j < k)

    by_name = {}
    for topic in answer["topics"]:
        by_name.setdefault(name_key(topic["name"]), []).append(topic)
    merged = []
    for members in by_name.values():
        members = sorted(members, key=lambda topic: -valid_size(topic))
        merged.append({"name": members[0]["name"], "definition": members[0]["definition"],
                       "clusters": list(dict.fromkeys(j for topic in members for j in topic["clusters"]))})
    claimed, topics = set(), []
    for topic in sorted(merged, key=lambda topic: -valid_size(topic)):
        if CATCH_ALL_NAME.fullmatch(name_key(topic["name"])) or not topic["name"].strip():
            continue
        if len(topics) >= MAX_TOPICS:
            break
        mine = list(dict.fromkeys(j for j in topic["clusters"] if 0 <= j < k and j not in claimed))
        if not mine:
            continue
        claimed.update(mine)
        topics.append({"name": topic["name"].strip(), "definition": topic["definition"].strip(), "clusters": mine})
    return topics, [j for j in range(k) if j not in claimed]


def assign(x, centres, topics, other):
    """PrototypeAssigner: Other prototypes within the gate of a topic prototype dropped; the nearest prototype at or
    above the minimum cosine labels a trace, a known-Other one or none labels it Other (-1)."""
    topic_groups = [j for topic in topics for j in topic["clusters"]]
    owners = [t for t, topic in enumerate(topics) for _ in topic["clusters"]]
    topic_vectors = centres[topic_groups].astype(np.float64)
    kept = [j for j in other
            if not topic_groups or (topic_vectors @ centres[j].astype(np.float64)).max() < OTHER_GATE]
    prototypes = np.vstack([topic_vectors, centres[kept].astype(np.float64)])
    owner = np.asarray(owners + [-1] * len(kept))
    sims = x.astype(np.float64) @ prototypes.T
    best = sims.argmax(1)
    labels = np.where(sims[np.arange(len(x)), best] >= ASSIGN_MIN_COSINE, owner[best], -1)
    prototypes = {"topic_prototypes": topic_groups, "owners": owners, "other_prototypes": kept,
                  "gated_other": [j for j in other if j not in kept]}
    return [int(v) for v in labels], prototypes


def build(answer, x, inverse, counts, intents, recent, groups, centres, k):
    """Summaries, the applied answer, the prototypes and the final labels of one grouping."""
    trace_groups = groups[inverse]
    summ = summaries(intents, x, trace_groups, centres, recent)
    topics, other = apply_author(answer, [s["size"] for s in summ])
    labels, prototypes = assign(x, centres, topics, other)
    return summ, topics, other, prototypes, labels


def compact(value, indent: int = 0) -> str:
    """JSON with one key per line and one object per line in a list of objects; everything else inline."""
    pad = " " * (indent + 1)
    if isinstance(value, dict):
        lines = [f"{pad}{json.dumps(key)}: {compact(item, indent + 1)}" for key, item in value.items()]
        return "{\n" + ",\n".join(lines) + "\n" + " " * indent + "}"
    if isinstance(value, list) and value and all(isinstance(item, dict) for item in value):
        lines = [pad + "  " + json.dumps(item, ensure_ascii=False) for item in value]
        return "[\n" + ",\n".join(lines) + "\n" + " " * indent + "]"
    return json.dumps(value, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bf16-from", type=Path)
    args = parser.parse_args()
    if args.bf16_from:
        raw = np.fromfile(args.bf16_from, dtype="<f4").reshape(-1, DIMENSIONS)
        bf16_as_read(raw).astype("<f4").tofile(HERE / "window.f32")

    window = json.loads((HERE / "window.json").read_text())
    intents = [trace["intent"] for trace in window["traces"]]
    x = np.fromfile(HERE / "window.f32", dtype="<f4").reshape(-1, DIMENSIONS)
    assert len(x) == len(intents)
    night = datetime.fromisoformat(window["night"].replace("Z", "+00:00"))
    recent = np.asarray([datetime.fromisoformat(trace["created_at"].replace("Z", "+00:00"))
                         >= night - timedelta(days=RECENT_DAYS) for trace in window["traces"]])
    unique, inverse, counts, first = merge(intents)
    xu = x[first]

    seeds = []
    for seed in range(SEEDS):
        fit = KMeans(K, n_init=3, random_state=seed).fit(xu, sample_weight=counts)
        seeds.append({"seed": seed, "inertia": float(fit.inertia_), "labels": fit.labels_.astype(int).tolist(),
                      "centres": fit.cluster_centers_})
    aris = [adjusted_rand_score(seeds[0]["labels"], s["labels"]) for s in seeds[1:]]
    groups = np.asarray(seeds[0]["labels"])
    centres = seeds[0]["centres"]
    centres = (centres / np.linalg.norm(centres, axis=1, keepdims=True)).astype(np.float32)
    recorded = json.loads((HERE / "answer.json").read_text())
    answer = answer_on(recorded, unique, counts, groups, K)
    summ, topics, other, prototypes, labels = build(answer, x, inverse, counts, intents, recent, groups, centres, K)

    java = json.loads((HERE / "java_kmeans.json").read_text())
    java_groups = np.asarray(java["groups"])
    assert java["k"] == K and len(java_groups) == len(unique)
    java_centres = unit_centres(xu, counts, java_groups, K)
    java_answer = answer_on(recorded, unique, counts, java_groups, K)
    _, java_topics, java_other, java_prototypes, java_labels = build(
        java_answer, x, inverse, counts, intents, recent, java_groups, java_centres, K)

    centres.astype("<f4").tofile(HERE / "centres.f32")
    java_centres.astype("<f4").tofile(HERE / "first_build_centres.f32")
    expected = {
        "unique_texts": unique, "unique_counts": counts.astype(int).tolist(), "unique_first": first.astype(int).tolist(),
        "k": K,
        "sklearn": {"labels": groups.astype(int).tolist(),
                    "seeds": [{k: v for k, v in s.items() if k != "centres"} for s in seeds],
                    "best_inertia": min(s["inertia"] for s in seeds),
                    "seed_ari_vs_seed0": {"min": min(aris), "mean": float(np.mean(aris))}},
        "answer": answer, "summaries": summ, "topics": topics, "other": other,
        "prototypes": {key: prototypes[key] for key in ("topic_prototypes", "other_prototypes", "gated_other")},
        "final_labels": labels,
        "first_build": {"groups": java_groups.astype(int).tolist(), "answer": java_answer, "topics": java_topics,
                        "other": java_other, "prototypes": java_prototypes, "labels": java_labels},
    }
    (HERE / "expected.json").write_text(compact(expected) + "\n")


if __name__ == "__main__":
    main()

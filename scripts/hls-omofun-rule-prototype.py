#!/usr/bin/env python3
from __future__ import annotations

import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
LABELS = ROOT / "local-omofun-labels-20260617.jsonl"
FEATURES = ROOT / "local/hls-feature-probe/local-ts/omofun-focused-boundary.json"


def load_rows() -> list[dict]:
    rows: list[dict] = []
    for line in LABELS.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("sid") is None:
            match = re.search(r"/sid/(\d+)/", row.get("pageUrl", ""))
            if match:
                row["sid"] = int(match.group(1))
        label = row.get("label")
        if isinstance(label, dict):
            row["label"] = label.get("label")
        rows.append(row)
    return rows


def basename(row: dict) -> str:
    return row["segment"].get("fileName", "").split("?", 1)[0]


def numeric_model(rows: list[dict]) -> str | None:
    values = []
    for row in rows:
        match = re.match(r"^(.*?)(\d{1,7})\.ts$", basename(row))
        if match:
            values.append((match.group(1), int(match.group(2))))
    if not values:
        return None

    prefix, count = Counter(prefix for prefix, _ in values).most_common(1)[0]
    if count / len(rows) < 0.8:
        return None

    numbers = [number for candidate, number in values if candidate == prefix]
    deltas = [b - a for a, b in zip(numbers, numbers[1:])]
    if not deltas:
        return None

    delta, delta_count = Counter(deltas).most_common(1)[0]
    if delta != 1 or delta_count / len(deltas) < 0.5:
        return None
    return prefix


def trailing_num(name: str, prefix: str | None) -> int | None:
    if not prefix or not name.startswith(prefix) or not name.endswith(".ts"):
        return None
    suffix = name[len(prefix) : -3]
    return int(suffix) if suffix.isdigit() else None


def group_label(rows: list[dict], group: dict) -> str:
    labels = [rows[index].get("label") for index in range(group["start"], group["end"] + 1)]
    counts = Counter(labels)
    if counts["ad"] and counts["ad"] >= max(1, 0.5 * group["count"]):
        return "ad"
    if counts["ok_overlay_ad"]:
        return "overlay"
    if counts["ok_fp"]:
        return "fp"
    if counts["ok_clean"] == group["count"]:
        return "clean"
    return "unknown"


def load_features() -> dict:
    if not FEATURES.exists():
        return {"segments": {}}
    return json.loads(FEATURES.read_text(encoding="utf-8"))


def audio_vec(features: dict, sid: int, index: int) -> list[float] | None:
    segment = features.get("segments", {}).get(f"{sid}:{index}")
    if not segment:
        return None
    audio = segment.get("audio", {})
    if not audio.get("ok"):
        return None
    return [
        audio["rms_db"] / 20,
        audio["peak_db"] / 20,
        audio["silence"],
        audio["zcr"] * 10,
        audio["centroid"] / 4000,
        audio["rolloff"] / 8000,
        audio["flatness"] * 5,
    ]


def mean_audio(features: dict, sid: int, start: int, end: int) -> list[float] | None:
    vectors = [
        vector
        for vector in (audio_vec(features, sid, index) for index in range(start, end + 1))
        if vector is not None
    ]
    if not vectors:
        return None
    return [sum(vector[column] for vector in vectors) / len(vectors) for column in range(len(vectors[0]))]


def distance(left: list[float] | None, right: list[float] | None) -> float | None:
    if left is None or right is None:
        return None
    return math.sqrt(sum((a - b) ** 2 for a, b in zip(left, right)))


def hls_candidates(sid: int, rows: list[dict]) -> list[dict]:
    groups = rows[0].get("groups") or []
    dense = len(groups) >= 20 or bool(groups and len(groups) / len(rows) > 0.08)
    group_files = [
        tuple(basename(rows[index]) for index in range(group["start"], group["end"] + 1))
        for group in groups
    ]
    repeats = Counter(group_files)
    seq_prefix = numeric_model(rows)
    result = []

    for group_index, group in enumerate(groups):
        segments = rows[group["start"] : group["end"] + 1]
        paths = " ".join(urlparse(segment["segment"]["uri"]).path.lower() for segment in segments)
        strong_path = any(token in paths for token in ["adjump", "/ad/", "/ads/", "advert"])
        repeat_short = (
            repeats[group_files[group_index]] > 1 and group["count"] <= 12 and group["duration"] <= 45
        )
        short = group["count"] <= 12 or group["duration"] <= 45
        prev_group = groups[group_index - 1] if group_index > 0 else None
        next_group = groups[group_index + 1] if group_index + 1 < len(groups) else None
        sandwiched = bool(
            prev_group
            and next_group
            and prev_group["duration"] >= 60
            and next_group["duration"] >= 60
            and group["duration"] <= 45
            and group["count"] >= 2
        )
        low_density_short = not dense and short and group["count"] >= 2
        dense_tiny = (
            dense
            and (group["count"] <= 3 or group["duration"] <= 12)
            and prev_group is not None
            and next_group is not None
        )

        numeric_jump = False
        if dense and seq_prefix:
            names = [basename(segment) for segment in segments]
            first = trailing_num(names[0], seq_prefix)
            last = trailing_num(names[-1], seq_prefix)
            previous = (
                trailing_num(basename(rows[group["start"] - 1]), seq_prefix)
                if group["start"] > 0
                else None
            )
            following = (
                trailing_num(basename(rows[group["end"] + 1]), seq_prefix)
                if group["end"] + 1 < len(rows)
                else None
            )
            numeric_jump = (
                (first is not None and previous is not None and abs(first - previous) > 1000)
                or (last is not None and following is not None and abs(following - last) > 1000)
            ) and short

        reasons = []
        if strong_path:
            reasons.append("strong_path")
        if repeat_short:
            reasons.append("repeat_short")
        if sandwiched:
            reasons.append("sandwiched_short")
        if low_density_short:
            reasons.append("low_density_short")
        if numeric_jump:
            reasons.append("numeric_jump")
        if dense_tiny:
            reasons.append("dense_tiny")

        result.append(
            {
                **group,
                "sid": sid,
                "groupIndex": group_index,
                "dense": dense,
                "label": group_label(rows, group),
                "reasons": reasons,
                "hlsCandidate": bool(reasons),
                "decision": "keep",
                "decisionReason": None,
            }
        )
    return result


def apply_audio_demotion(features: dict, sid: int, groups: list[dict]) -> list[dict]:
    index = 0
    while index < len(groups):
        if "numeric_jump" not in groups[index]["reasons"]:
            index += 1
            continue
        end = index
        while end < len(groups) and "numeric_jump" in groups[end]["reasons"]:
            end += 1

        left = groups[index - 1] if index > 0 else None
        right = groups[end] if end < len(groups) else None
        left_audio = mean_audio(features, sid, left["start"], left["end"]) if left else None
        right_audio = mean_audio(features, sid, right["start"], right["end"]) if right else None

        for group in groups[index:end]:
            group_audio = mean_audio(features, sid, group["start"], group["end"])
            left_distance = distance(group_audio, left_audio)
            right_distance = distance(group_audio, right_audio)
            if left_distance is None or right_distance is None:
                group["decision"] = "review"
                group["decisionReason"] = "numeric_jump_no_audio"
            elif left_distance >= 0.55 and right_distance >= 0.55:
                group["decision"] = "remove"
                group["decisionReason"] = (
                    f"numeric_jump_audio_outlier dl={left_distance:.2f} dr={right_distance:.2f}"
                )
            else:
                group["decision"] = "keep"
                group["decisionReason"] = (
                    f"numeric_jump_audio_continuous dl={left_distance:.2f} dr={right_distance:.2f}"
                )
        index = end

    for group in groups:
        if group["decision"] == "keep" and group["hlsCandidate"] and "numeric_jump" not in group["reasons"]:
            group["decision"] = "remove"
            group["decisionReason"] = "+".join(group["reasons"])
    return groups


def main() -> None:
    rows_by_sid = defaultdict(list)
    for row in load_rows():
        rows_by_sid[row["sid"]].append(row)
    features = load_features()

    all_groups = []
    for sid in sorted(rows_by_sid):
        groups = apply_audio_demotion(features, sid, hls_candidates(sid, rows_by_sid[sid]))
        all_groups.extend(groups)
        print(f"\nSID {sid}")
        for group in groups:
            if group["hlsCandidate"] or group["label"] in {"ad", "overlay", "fp"}:
                print(
                    f"  g{group['groupIndex']} #{group['start']}-{group['end']} "
                    f"label={group['label']} hls={group['reasons']} "
                    f"decision={group['decision']} reason={group['decisionReason']}"
                )

    true_positive = sum(group["decision"] == "remove" and group["label"] == "ad" for group in all_groups)
    false_positive = sum(group["decision"] == "remove" and group["label"] != "ad" for group in all_groups)
    false_negative = sum(group["decision"] != "remove" and group["label"] == "ad" for group in all_groups)
    print("\nmetrics")
    print(
        json.dumps(
            {
                "truePositive": true_positive,
                "falsePositive": false_positive,
                "falseNegative": false_negative,
                "precision": true_positive / (true_positive + false_positive)
                if true_positive + false_positive
                else None,
                "recall": true_positive / (true_positive + false_negative)
                if true_positive + false_negative
                else None,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

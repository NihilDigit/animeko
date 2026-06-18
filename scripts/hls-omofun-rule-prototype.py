#!/usr/bin/env python3
from __future__ import annotations

import json
import math
import re
import argparse
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
LABELS = ROOT / "local-omofun-labels-20260617.jsonl"
FEATURES = ROOT / "local/hls-feature-probe/local-ts/omofun-focused-boundary.json"
AUDIO_BACKSTOP_DISTANCE_THRESHOLD = 0.55


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

        sequence_island = False
        if dense and seq_prefix:
            names = [basename(segment) for segment in segments]
            numbers = [trailing_num(name, seq_prefix) for name in names]
            first = numbers[0]
            last = numbers[-1]
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
            linear_island = all(number is not None for number in numbers) and all(
                numbers[index + 1] == numbers[index] + 1 for index in range(len(numbers) - 1)
            )
            sequence_island = (
                previous is not None
                and following is not None
                and first is not None
                and last is not None
                and linear_island
                and following == previous + 1
                and abs(first - previous) > 1000
                and abs(last - following) > 1000
                and group["count"] >= 2
                and short
            )

        reasons = []
        if strong_path:
            reasons.append("strong_path")
        if repeat_short:
            reasons.append("repeat_short")
        if sandwiched:
            reasons.append("sandwiched_short")
        if low_density_short:
            reasons.append("low_density_short")
        if sequence_island:
            reasons.append("sequence_island")
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
            elif (
                left_distance >= AUDIO_BACKSTOP_DISTANCE_THRESHOLD
                and right_distance >= AUDIO_BACKSTOP_DISTANCE_THRESHOLD
            ):
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


def apply_aggressive_audio_backstop(features: dict, sid: int, groups: list[dict]) -> list[dict]:
    groups = apply_aggressive_candidates(groups)
    for index, group in enumerate(groups):
        if not group["aggressiveCandidate"]:
            group["decision"] = "keep"
            group["decisionReason"] = None
            continue

        left_distance, right_distance = neighbor_audio_distances(features, sid, groups, index)
        distances = [value for value in [left_distance, right_distance] if value is not None]
        min_distance = min(distances) if distances else None
        group["audioLeftDistance"] = left_distance
        group["audioRightDistance"] = right_distance
        group["audioMinNeighborDistance"] = min_distance

        if min_distance is not None and min_distance < AUDIO_BACKSTOP_DISTANCE_THRESHOLD:
            group["decision"] = "keep"
            group["decisionReason"] = (
                f"aggressive_audio_backstop_continuous "
                f"min={min_distance:.2f} threshold={AUDIO_BACKSTOP_DISTANCE_THRESHOLD:.2f}"
            )
        else:
            group["decision"] = "remove"
            if min_distance is None:
                group["decisionReason"] = "aggressive_audio_backstop_no_audio"
            else:
                group["decisionReason"] = (
                    f"aggressive_audio_backstop_outlier "
                    f"min={min_distance:.2f} threshold={AUDIO_BACKSTOP_DISTANCE_THRESHOLD:.2f}"
                )
    return groups


def apply_aggressive_candidates(groups: list[dict]) -> list[dict]:
    for group in groups:
        reasons = list(group["reasons"])
        if group["groupIndex"] != 0 and (group["count"] <= 30 or group["duration"] <= 180):
            reasons.append("aggressive_short_or_small")
        group["aggressiveReasons"] = list(dict.fromkeys(reasons))
        group["aggressiveCandidate"] = bool(group["aggressiveReasons"])
    return groups


def neighbor_audio_distances(features: dict, sid: int, groups: list[dict], index: int) -> tuple[float | None, float | None]:
    group = groups[index]
    left = groups[index - 1] if index > 0 else None
    right = groups[index + 1] if index + 1 < len(groups) else None
    group_audio = mean_audio(features, sid, group["start"], group["end"])
    left_distance = distance(group_audio, mean_audio(features, sid, left["start"], left["end"])) if left else None
    right_distance = distance(group_audio, mean_audio(features, sid, right["start"], right["end"])) if right else None
    return left_distance, right_distance


def run_aggressive_audio_sweep(rows_by_sid: dict[int, list[dict]], features: dict) -> None:
    labeled = []
    for sid in sorted(rows_by_sid):
        groups = apply_aggressive_candidates(hls_candidates(sid, rows_by_sid[sid]))
        for index, group in enumerate(groups):
            if group["label"] not in {"ad", "fp", "overlay", "clean"}:
                continue
            if not group["aggressiveCandidate"]:
                continue
            left_distance, right_distance = neighbor_audio_distances(features, sid, groups, index)
            distances = [value for value in [left_distance, right_distance] if value is not None]
            labeled.append(
                {
                    **group,
                    "sid": sid,
                    "leftDistance": left_distance,
                    "rightDistance": right_distance,
                    "minNeighborDistance": min(distances) if distances else None,
                }
            )

    print("\naggressive labeled candidates")
    print(json.dumps(Counter(group["label"] for group in labeled), ensure_ascii=False, indent=2))
    for group in labeled:
        print(
            f"  sid{group['sid']} g{group['groupIndex']} #{group['start']}-{group['end']} "
            f"label={group['label']} base={group['reasons']} aggressive={group['aggressiveReasons']} "
            f"dl={format_distance(group['leftDistance'])} dr={format_distance(group['rightDistance'])} "
            f"min={format_distance(group['minNeighborDistance'])}"
        )

    print("\naggressive audio backstop sweep")
    print(
        "demote when min neighbor distance is below threshold; no-audio candidates remain remove; "
        f"hardened threshold={AUDIO_BACKSTOP_DISTANCE_THRESHOLD:.2f}"
    )
    for threshold in [0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70]:
        true_positive = false_positive = false_negative = true_negative = 0
        for group in labeled:
            remove = True
            min_distance = group["minNeighborDistance"]
            if min_distance is not None and min_distance < threshold:
                remove = False

            positive = group["label"] == "ad"
            if remove and positive:
                true_positive += 1
            elif remove and not positive:
                false_positive += 1
            elif not remove and positive:
                false_negative += 1
            else:
                true_negative += 1

        precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else None
        recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else None
        print(
            f"  threshold={threshold:.2f} TP={true_positive} FP={false_positive} "
            f"FN={false_negative} TN={true_negative} "
            f"precision={format_metric(precision)} recall={format_metric(recall)}"
        )


def format_distance(value: float | None) -> str:
    return "NA" if value is None else f"{value:.2f}"


def format_metric(value: float | None) -> str:
    return "NA" if value is None else f"{value:.3f}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--mode",
        choices=["current", "aggressive-audio"],
        default="current",
        help="rule mode to evaluate against labeled omofun data",
    )
    parser.add_argument(
        "--aggressive-audio-sweep",
        action="store_true",
        help="also run a labeled-set sweep for aggressive structural candidates with audio demotion thresholds",
    )
    args = parser.parse_args()

    rows_by_sid = defaultdict(list)
    for row in load_rows():
        rows_by_sid[row["sid"]].append(row)
    features = load_features()

    all_groups = []
    for sid in sorted(rows_by_sid):
        candidates = hls_candidates(sid, rows_by_sid[sid])
        if args.mode == "aggressive-audio":
            groups = apply_aggressive_audio_backstop(features, sid, candidates)
        else:
            groups = apply_audio_demotion(features, sid, candidates)
        all_groups.extend(groups)
        print(f"\nSID {sid}")
        for group in groups:
            is_candidate = group["aggressiveCandidate"] if args.mode == "aggressive-audio" else group["hlsCandidate"]
            reasons = group["aggressiveReasons"] if args.mode == "aggressive-audio" else group["reasons"]
            if is_candidate or group["label"] in {"ad", "overlay", "fp"}:
                print(
                    f"  g{group['groupIndex']} #{group['start']}-{group['end']} "
                    f"label={group['label']} hls={reasons} "
                    f"decision={group['decision']} reason={group['decisionReason']}"
                )

    labeled_groups = [group for group in all_groups if group["label"] in {"ad", "fp", "overlay", "clean"}]
    true_positive = sum(group["decision"] == "remove" and group["label"] == "ad" for group in labeled_groups)
    false_positive = sum(group["decision"] == "remove" and group["label"] != "ad" for group in labeled_groups)
    false_negative = sum(group["decision"] != "remove" and group["label"] == "ad" for group in labeled_groups)
    unknown_remove = sum(group["decision"] == "remove" and group["label"] == "unknown" for group in all_groups)
    print("\nmetrics")
    print(
        json.dumps(
            {
                "truePositive": true_positive,
                "falsePositive": false_positive,
                "falseNegative": false_negative,
                "unknownRemove": unknown_remove,
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

    if args.aggressive_audio_sweep:
        run_aggressive_audio_sweep(rows_by_sid, features)


if __name__ == "__main__":
    main()

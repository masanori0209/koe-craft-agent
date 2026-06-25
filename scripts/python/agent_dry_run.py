#!/usr/bin/env python3
import json
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    fixture = json.loads((ROOT / "examples" / "cases" / "place_torch_missing.json").read_text())
    tasks = [
        "collect_log",
        "pickup_items",
        "craft_planks",
        "craft_sticks",
        "craft_crafting_table",
        "open_crafting_table",
        "craft_wooden_pickaxe",
        "close_screen",
        "collect_cobblestone",
        "pickup_items",
        "open_crafting_table",
        "craft_furnace",
        "close_screen",
        "open_furnace",
        "smelt_charcoal",
        "close_screen",
        "craft_torch",
        "ensure_torch_hotbar",
        "place_torch",
    ]
    trace = {
        "recognized_text": fixture["recognized_text"],
        "goal": {"type": "place_light", "target_item": "minecraft:torch"},
        "selected_route": "charcoal_route",
        "tasks": tasks,
        "safety": {"valid": True, "errors": []},
        "explanation": "松明と石炭がないため、周囲の木と石からかまどを作り、木炭を焼いて松明を作る計画を選びました。",
    }
    trace_dir = ROOT / "logs" / "traces"
    trace_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%S-%fZ")
    trace_path = trace_dir / f"{stamp}-charcoal_route.json"
    trace_path.write_text(json.dumps(trace, ensure_ascii=False, indent=2) + "\n")
    trace["trace_path"] = str(trace_path.relative_to(ROOT))
    print(json.dumps(trace, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

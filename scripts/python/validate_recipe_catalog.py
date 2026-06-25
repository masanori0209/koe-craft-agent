#!/usr/bin/env python3
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


def main() -> None:
    data_catalog = load_json(ROOT / "data" / "minecraft" / "vanilla_recipes.json")
    crafting = load_json(ROOT / "mod" / "src" / "main" / "resources" / "koecraft" / "vanilla_crafting_recipes.json")
    terms = load_json(ROOT / "mod" / "src" / "main" / "resources" / "koecraft" / "vanilla_terms.json")
    required_outputs = {
        "minecraft:torch",
        "minecraft:stone_pickaxe",
        "minecraft:crafting_table",
        "minecraft:furnace",
        "minecraft:shield",
        "minecraft:bucket",
        "minecraft:flint_and_steel",
        "minecraft:paper",
        "minecraft:book",
        "minecraft:arrow",
    }
    outputs = {
        recipe.get("result", {}).get("id")
        for recipe in data_catalog.get("recipes", [])
        if isinstance(recipe, dict)
    }
    bundled_outputs = {
        recipe.get("result", {}).get("id")
        for recipe in crafting.get("recipes", [])
        if isinstance(recipe, dict)
    }
    missing = sorted(required_outputs - outputs)
    missing_bundled = sorted(required_outputs - bundled_outputs)
    if missing:
        raise AssertionError(f"data recipe catalog missing outputs: {missing}")
    if missing_bundled:
        raise AssertionError(f"bundled mod recipe catalog missing outputs: {missing_bundled}")
    if not terms.get("terms"):
        raise AssertionError("vanilla_terms.json has no terms")
    print("[recipe-catalog] vanilla catalog checks passed")


if __name__ == "__main__":
    main()

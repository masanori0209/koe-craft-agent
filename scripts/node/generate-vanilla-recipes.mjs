import { mkdirSync, writeFileSync } from "node:fs";
import { join, basename } from "node:path";
import { spawnSync } from "node:child_process";
import { repoRoot } from "./lib.mjs";

const version = argValue("--version") ?? "1.21.4";
const jarPath =
  argValue("--jar") ??
  `${process.env.HOME}/.gradle/caches/fabric-loom/${version}/minecraft-merged.jar`;

const outDir = join(repoRoot, "data", "minecraft");
const modOutDir = join(repoRoot, "mod", "src", "main", "resources", "koecraft");
mkdirSync(outDir, { recursive: true });
mkdirSync(modOutDir, { recursive: true });

const recipePaths = exec("jar", ["tf", jarPath])
  .split("\n")
  .filter((path) => /^data\/minecraft\/recipe\/.+\.json$/.test(path))
  .sort();

if (recipePaths.length === 0) {
  throw new Error(`No vanilla recipe JSON files found in ${jarPath}`);
}

const recipes = [];
const craftingRecipes = [];
const furnaceRecipes = [];
const stonecuttingRecipes = [];
const smithingRecipes = [];
const smithingTrimRecipes = [];
const allTags = new Set();
const allItems = new Set();

for (const path of recipePaths) {
  const raw = JSON.parse(exec("unzip", ["-p", jarPath, path]));
  const recipeId = `minecraft:${basename(path, ".json")}`;
  const smithingTrimRecipe = toSmithingTrimRecipe(recipeId, raw);
  if (smithingTrimRecipe) {
    smithingTrimRecipes.push(smithingTrimRecipe);
  }
  const result = parseResult(raw.result);
  if (!result.id) continue;
  const refs = collectRefs(raw);
  for (const tag of refs.tags) allTags.add(tag);
  for (const item of refs.items) allItems.add(item);
  recipes.push({
    recipe_id: recipeId,
    path,
    type: raw.type ?? "",
    station: stationForType(raw.type ?? ""),
    category: raw.category ?? "",
    group: raw.group ?? "",
    result,
    ingredients: refs.ingredients,
    item_refs: refs.items,
    tag_refs: refs.tags
  });
  const craftingRecipe = toCraftingRecipe(recipeId, raw, result);
  if (craftingRecipe) {
    craftingRecipes.push(craftingRecipe);
  }
  const furnaceRecipe = toFurnaceRecipe(recipeId, raw, result);
  if (furnaceRecipe) {
    furnaceRecipes.push(furnaceRecipe);
  }
  const stonecuttingRecipe = toStonecuttingRecipe(recipeId, raw, result);
  if (stonecuttingRecipe) {
    stonecuttingRecipes.push(stonecuttingRecipe);
  }
  const smithingRecipe = toSmithingRecipe(recipeId, raw, result);
  if (smithingRecipe) {
    smithingRecipes.push(smithingRecipe);
  }
}

recipes.sort((a, b) => a.recipe_id.localeCompare(b.recipe_id));
craftingRecipes.sort((a, b) => a.recipe_id.localeCompare(b.recipe_id));
furnaceRecipes.sort((a, b) => a.recipe_id.localeCompare(b.recipe_id));
stonecuttingRecipes.sort((a, b) => a.recipe_id.localeCompare(b.recipe_id));
smithingRecipes.sort((a, b) => a.recipe_id.localeCompare(b.recipe_id));
smithingTrimRecipes.sort((a, b) => a.recipe_id.localeCompare(b.recipe_id));

const byOutput = {};
for (const recipe of recipes) {
  byOutput[recipe.result.id] ??= [];
  byOutput[recipe.result.id].push({
    recipe_id: recipe.recipe_id,
    count: recipe.result.count,
    type: recipe.type,
    station: recipe.station
  });
}

const catalog = {
  version,
  generated_at: new Date().toISOString(),
  source_jar: jarPath,
  recipe_count: recipes.length,
  output_count: Object.keys(byOutput).length,
  tags: [...allTags].sort(),
  item_refs: [...allItems].sort(),
  recipes,
  by_output: Object.fromEntries(Object.entries(byOutput).sort(([a], [b]) => a.localeCompare(b)))
};

writeFileSync(join(outDir, "vanilla_recipes.json"), `${JSON.stringify(catalog, null, 2)}\n`);
writeFileSync(join(modOutDir, "vanilla_crafting_recipes.json"), `${JSON.stringify({
  version,
  generated_at: catalog.generated_at,
  recipe_count: craftingRecipes.length,
  recipes: craftingRecipes
}, null, 2)}\n`);
writeFileSync(join(modOutDir, "vanilla_furnace_recipes.json"), `${JSON.stringify({
  version,
  generated_at: catalog.generated_at,
  recipe_count: furnaceRecipes.length,
  recipes: furnaceRecipes
}, null, 2)}\n`);
writeFileSync(join(modOutDir, "vanilla_stonecutting_recipes.json"), `${JSON.stringify({
  version,
  generated_at: catalog.generated_at,
  recipe_count: stonecuttingRecipes.length,
  recipes: stonecuttingRecipes
}, null, 2)}\n`);
writeFileSync(join(modOutDir, "vanilla_smithing_recipes.json"), `${JSON.stringify({
  version,
  generated_at: catalog.generated_at,
  recipe_count: smithingRecipes.length,
  recipes: smithingRecipes
}, null, 2)}\n`);
writeFileSync(join(modOutDir, "vanilla_smithing_trim_recipes.json"), `${JSON.stringify({
  version,
  generated_at: catalog.generated_at,
  recipe_count: smithingTrimRecipes.length,
  recipes: smithingTrimRecipes
}, null, 2)}\n`);
console.log(`[vanilla-recipes] ${recipes.length} recipes generated for Minecraft ${version}`);
console.log("[vanilla-recipes] data/minecraft/vanilla_recipes.json");
console.log("[vanilla-recipes] mod/src/main/resources/koecraft/vanilla_crafting_recipes.json");
console.log("[vanilla-recipes] mod/src/main/resources/koecraft/vanilla_furnace_recipes.json");
console.log("[vanilla-recipes] mod/src/main/resources/koecraft/vanilla_stonecutting_recipes.json");
console.log("[vanilla-recipes] mod/src/main/resources/koecraft/vanilla_smithing_recipes.json");
console.log("[vanilla-recipes] mod/src/main/resources/koecraft/vanilla_smithing_trim_recipes.json");

function argValue(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

function exec(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed: ${result.stderr}`);
  }
  return result.stdout;
}

function parseResult(result) {
  if (typeof result === "string") return { id: normalizeId(result), count: 1 };
  if (!result || typeof result !== "object") return { id: "", count: 0 };
  return {
    id: normalizeId(result.id ?? result.item ?? ""),
    count: Number(result.count ?? 1)
  };
}

function stationForType(type) {
  if (type.includes("smelting") || type.includes("blasting") || type.includes("smoking") || type.includes("campfire_cooking")) return "furnace_family";
  if (type.includes("stonecutting")) return "stonecutter";
  if (type.includes("smithing")) return "smithing_table";
  if (type.includes("crafting")) return "crafting";
  return "other";
}

function collectRefs(raw) {
  const ingredients = [];
  const items = new Set();
  const tags = new Set();

  function addRef(value) {
    if (typeof value !== "string") return;
    const normalized = normalizeId(value);
    if (normalized.startsWith("#")) {
      tags.add(normalized.slice(1));
      ingredients.push({ tag: normalized.slice(1) });
    } else if (normalized.startsWith("minecraft:")) {
      items.add(normalized);
      ingredients.push({ item: normalized });
    }
  }

  if (raw.key && typeof raw.key === "object") {
    for (const value of Object.values(raw.key)) collectIngredientValue(value, addRef);
  }
  if (Array.isArray(raw.ingredients)) {
    for (const value of raw.ingredients) collectIngredientValue(value, addRef);
  }
  if (raw.ingredient) collectIngredientValue(raw.ingredient, addRef);
  if (raw.input) collectIngredientValue(raw.input, addRef);
  if (raw.material) collectIngredientValue(raw.material, addRef);
  if (raw.base) collectIngredientValue(raw.base, addRef);
  if (raw.addition) collectIngredientValue(raw.addition, addRef);
  if (raw.template) collectIngredientValue(raw.template, addRef);

  return {
    ingredients: uniqueIngredients(ingredients),
    items: [...items].sort(),
    tags: [...tags].sort()
  };
}

function collectIngredientValue(value, addRef) {
  if (typeof value === "string") {
    addRef(value);
    return;
  }
  if (Array.isArray(value)) {
    for (const entry of value) collectIngredientValue(entry, addRef);
    return;
  }
  if (!value || typeof value !== "object") return;
  if (value.item) addRef(value.item);
  if (value.tag) addRef(`#${value.tag}`);
  if (value.id) addRef(value.id);
}

function toCraftingRecipe(recipeId, raw, result) {
  if (raw.type === "minecraft:crafting_shaped") {
    if (!Array.isArray(raw.pattern) || !raw.key || typeof raw.key !== "object") return null;
    const key = {};
    for (const [symbol, ingredient] of Object.entries(raw.key)) {
      key[symbol] = normalizeIngredientAlternatives(ingredient);
    }
    return {
      recipe_id: recipeId,
      type: raw.type,
      group: raw.group ?? "",
      result,
      pattern: raw.pattern,
      key
    };
  }
  if (raw.type === "minecraft:crafting_shapeless") {
    if (!Array.isArray(raw.ingredients)) return null;
    return {
      recipe_id: recipeId,
      type: raw.type,
      group: raw.group ?? "",
      result,
      ingredients: raw.ingredients.map(normalizeIngredientAlternatives)
    };
  }
  if (raw.type === "minecraft:crafting_transmute") {
    if (!raw.input || !raw.material) return null;
    return {
      recipe_id: recipeId,
      type: raw.type,
      group: raw.group ?? "",
      result,
      ingredients: [
        normalizeIngredientAlternatives(raw.input),
        normalizeIngredientAlternatives(raw.material)
      ]
    };
  }
  return null;
}

function toStonecuttingRecipe(recipeId, raw, result) {
  if (raw.type !== "minecraft:stonecutting" || !raw.ingredient) return null;
  return {
    recipe_id: recipeId,
    type: raw.type,
    result,
    ingredient: normalizeIngredientAlternatives(raw.ingredient)
  };
}

function toFurnaceRecipe(recipeId, raw, result) {
  if (!["minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking"].includes(raw.type)) return null;
  if (!raw.ingredient) return null;
  return {
    recipe_id: recipeId,
    type: raw.type,
    result,
    ingredient: normalizeIngredientAlternatives(raw.ingredient)
  };
}

function toSmithingRecipe(recipeId, raw, result) {
  if (raw.type !== "minecraft:smithing_transform") return null;
  if (!raw.template || !raw.base || !raw.addition) return null;
  return {
    recipe_id: recipeId,
    type: raw.type,
    result,
    template: normalizeIngredientAlternatives(raw.template),
    base: normalizeIngredientAlternatives(raw.base),
    addition: normalizeIngredientAlternatives(raw.addition)
  };
}

function toSmithingTrimRecipe(recipeId, raw) {
  if (raw.type !== "minecraft:smithing_trim") return null;
  if (!raw.template || !raw.base || !raw.addition) return null;
  return {
    recipe_id: recipeId,
    type: raw.type,
    template: normalizeIngredientAlternatives(raw.template),
    base: normalizeIngredientAlternatives(raw.base),
    addition: normalizeIngredientAlternatives(raw.addition)
  };
}

function normalizeIngredientAlternatives(value) {
  const alternatives = [];
  function add(value) {
    if (typeof value === "string") {
      const normalized = normalizeId(value);
      if (normalized.startsWith("#")) alternatives.push({ tag: normalized.slice(1) });
      else alternatives.push({ item: normalized });
      return;
    }
    if (Array.isArray(value)) {
      for (const entry of value) add(entry);
      return;
    }
    if (!value || typeof value !== "object") return;
    if (value.item) alternatives.push({ item: normalizeId(value.item) });
    if (value.tag) alternatives.push({ tag: normalizeId(value.tag) });
    if (value.id) add(value.id);
  }
  add(value);
  return alternatives;
}

function uniqueIngredients(ingredients) {
  const seen = new Set();
  const result = [];
  for (const ingredient of ingredients) {
    const key = JSON.stringify(ingredient);
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(ingredient);
  }
  return result;
}

function normalizeId(value) {
  const text = String(value);
  if (text.startsWith("#")) return `#${normalizeId(text.slice(1))}`;
  if (text.includes(":")) return text;
  return `minecraft:${text}`;
}

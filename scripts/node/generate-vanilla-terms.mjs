import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { repoRoot } from "./lib.mjs";

const version = process.argv.includes("--version")
  ? process.argv[process.argv.indexOf("--version") + 1]
  : "1.21.4";
const jarPath = process.argv.includes("--jar")
  ? process.argv[process.argv.indexOf("--jar") + 1]
  : `${process.env.HOME}/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2/minecraft-merged-1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2.jar`;

const outDir = join(repoRoot, "data", "minecraft");
const dictDir = join(repoRoot, "data", "amivoice");
mkdirSync(outDir, { recursive: true });
mkdirSync(dictDir, { recursive: true });

const versionManifest = await fetchJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
const versionInfo = versionManifest.versions.find((entry) => entry.id === version);
if (!versionInfo) throw new Error(`Minecraft version not found in manifest: ${version}`);
const versionJson = await fetchJson(versionInfo.url);
const assetIndex = await fetchJson(versionJson.assetIndex.url);
const jaLangObject = assetIndex.objects["minecraft/lang/ja_jp.json"];
if (!jaLangObject) throw new Error(`ja_jp.json not found in asset index for ${version}`);
const jaLangUrl = `https://resources.download.minecraft.net/${jaLangObject.hash.slice(0, 2)}/${jaLangObject.hash}`;
const ja = await fetchJson(jaLangUrl);
const en = JSON.parse(execUnzip(jarPath, "assets/minecraft/lang/en_us.json"));

const terms = [];
for (const [key, englishName] of Object.entries(en)) {
  const parsed = parseVanillaKey(key);
  if (!parsed) continue;
  const japaneseName = ja[key] ?? "";
  if (!japaneseName) continue;
  terms.push({
    id: parsed.id,
    kind: parsed.kind,
    key,
    en_us: englishName,
    ja_jp: japaneseName,
    reading: normalizeReadingCandidate(japaneseName),
    source: `minecraft-${version}`
  });
}
terms.sort((a, b) => a.kind.localeCompare(b.kind) || a.id.localeCompare(b.id));

writeFileSync(join(outDir, "vanilla_terms.json"), `${JSON.stringify({ version, generated_at: new Date().toISOString(), terms }, null, 2)}\n`);

const dictRows = [
  "# Generated from Minecraft vanilla language assets.",
  "# Format: surface<TAB>reading<TAB>category<TAB>minecraft_id<TAB>source",
  "# Reading is a conservative candidate. Review before bulk AmiVoice registration."
];
for (const term of terms) {
  if (!term.reading) continue;
  dictRows.push(`${term.ja_jp}\t${term.reading}\tvanilla_${term.kind}\t${term.id}\t${term.source}`);
}
writeFileSync(join(dictDir, "vanilla_dict.tsv"), `${dictRows.join("\n")}\n`);

console.log(`[vanilla-terms] ${terms.length} terms generated for Minecraft ${version}`);
console.log(`[vanilla-terms] data/minecraft/vanilla_terms.json`);
console.log(`[vanilla-terms] data/amivoice/vanilla_dict.tsv`);

function parseVanillaKey(key) {
  const match = /^(block|item|entity)\.minecraft\.([a-z0-9_.-]+)$/.exec(key);
  if (!match) return null;
  return {
    kind: match[1],
    id: `minecraft:${match[2]}`
  };
}

function normalizeReadingCandidate(surface) {
  const trimmed = String(surface).replace(/\s+/g, "");
  if (!trimmed) return "";
  // AmiVoice dictionary review can refine these. Keep katakana/kanji surfaces as-is for now;
  // validation for this generated file is intentionally separate from curated dict.txt.
  return trimmed;
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Failed to fetch ${url}: ${response.status}`);
  return await response.json();
}

function execUnzip(jar, path) {
  const result = spawnSync("unzip", ["-p", jar, path], { encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(`Failed to extract ${path} from ${jar}: ${result.stderr}`);
  }
  return result.stdout;
}

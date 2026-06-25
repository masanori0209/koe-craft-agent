import { readFileSync } from "node:fs";
import { join } from "node:path";
import { printStep, repoRoot } from "./lib.mjs";

printStep("AmiVoice dictionary check");

const path = join(repoRoot, "data", "amivoice", "dict.txt");
const text = readFileSync(path, "utf8");
const rows = text.split(/\r?\n/).filter((line) => line.trim().length > 0);
const seenSurfaceReading = new Set();
const errors = [];
const warnings = [];

for (const [index, row] of rows.entries()) {
  const columns = row.split("\t");
  const lineNo = index + 1;
  if (columns.length !== 3) {
    errors.push(`line ${lineNo}: expected 3 tab-separated columns: surface, reading, category`);
    continue;
  }
  const [surface, reading, category] = columns;
  if (!surface || !reading || !category) errors.push(`line ${lineNo}: empty column`);
  if (!/^[ぁ-んーA-Za-z0-9]+$/.test(reading)) {
    errors.push(`line ${lineNo}: reading must be hiragana/prolonged sound/ascii only: ${reading}`);
  }
  const key = `${surface}\t${reading}`;
  if (seenSurfaceReading.has(key)) warnings.push(`line ${lineNo}: duplicate surface+reading: ${surface}/${reading}`);
  seenSurfaceReading.add(key);
  if (reading.length <= 1) warnings.push(`line ${lineNo}: very short reading may increase false recognition: ${surface}/${reading}`);
}

for (const warning of warnings) console.warn(`[warn] ${warning}`);
if (errors.length > 0) {
  for (const error of errors) console.error(`[error] ${error}`);
  process.exit(1);
}

console.log(`[dict] ${rows.length} entries OK`);

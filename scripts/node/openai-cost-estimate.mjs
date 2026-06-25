import { readFileSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";
import { repoRoot } from "./lib.mjs";

const pricingPath = join(repoRoot, "data/pricing/openai-pricing.json");
const pricing = JSON.parse(readFileSync(pricingPath, "utf8"));

const scenarios = {
  "goal-parser-smoke": {
    description: "10 live utterances through the LLM goal parser",
    requests: 10,
    inputTokens: 1200,
    cachedInputTokens: 0,
    outputTokens: 250
  },
  "demo-session": {
    description: "100 live utterances during repeated demo practice",
    requests: 100,
    inputTokens: 1200,
    cachedInputTokens: 0,
    outputTokens: 250
  },
  "fixture-regression-live": {
    description: "50 fixture utterances replayed against a live LLM parser",
    requests: 50,
    inputTokens: 1400,
    cachedInputTokens: 0,
    outputTokens: 300
  }
};

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2);
    const value = argv[i + 1]?.startsWith("--") ? undefined : argv[i + 1];
    args[key] = value ?? "true";
    if (value !== undefined) i += 1;
  }
  return args;
}

function numberArg(args, key, fallback) {
  if (args[key] === undefined) return fallback;
  const value = Number(args[key]);
  if (!Number.isFinite(value) || value < 0) {
    throw new Error(`--${key} must be a non-negative number`);
  }
  return value;
}

function usage() {
  const modelNames = Object.keys(pricing.models).join(", ");
  const scenarioNames = Object.keys(scenarios).join(", ");
  console.log(`Usage:
  npm run openai:cost -- --scenario goal-parser-smoke
  npm run openai:cost -- --model gpt-5.4-mini --requests 100 --input-tokens 1200 --output-tokens 250 --usd-jpy 160

Options:
  --scenario <name>          One of: ${scenarioNames}
  --model <name>             One of: ${modelNames}
  --requests <n>             Number of live OpenAI requests
  --input-tokens <n>         Estimated uncached input tokens per request
  --cached-input-tokens <n>  Estimated cached input tokens per request
  --output-tokens <n>        Estimated output tokens per request
  --usd-jpy <n>              Optional exchange rate for JPY estimate
`);
}

const args = parseArgs(process.argv.slice(2));
if (args.help === "true" || args.h === "true") {
  usage();
  process.exit(0);
}

const hasCustomEstimate =
  args.requests !== undefined ||
  args["input-tokens"] !== undefined ||
  args["cached-input-tokens"] !== undefined ||
  args["output-tokens"] !== undefined;
const scenarioName = args.scenario ?? (hasCustomEstimate ? "custom" : "goal-parser-smoke");
const scenario = scenarioName === "custom" ? scenarios["goal-parser-smoke"] : scenarios[scenarioName];
if (!scenario) {
  console.error(`Unknown scenario: ${args.scenario}`);
  usage();
  process.exit(1);
}

const modelName = args.model ?? "gpt-4o-mini";
const model = pricing.models[modelName];
if (!model) {
  console.error(`Unknown model: ${modelName}`);
  usage();
  process.exit(1);
}

const requests = numberArg(args, "requests", scenario.requests);
const inputTokens = numberArg(args, "input-tokens", scenario.inputTokens);
const cachedInputTokens = numberArg(args, "cached-input-tokens", scenario.cachedInputTokens);
const outputTokens = numberArg(args, "output-tokens", scenario.outputTokens);
const usdJpy = args["usd-jpy"] === undefined ? undefined : numberArg(args, "usd-jpy", undefined);

const inputCost = (requests * inputTokens * model.input_per_1m) / 1_000_000;
const cachedInputCost = (requests * cachedInputTokens * model.cached_input_per_1m) / 1_000_000;
const outputCost = (requests * outputTokens * model.output_per_1m) / 1_000_000;
const totalUsd = inputCost + cachedInputCost + outputCost;

function money(value, digits = 6) {
  return value.toFixed(digits).replace(/\.?0+$/, "");
}

console.log("OpenAI cost estimate");
console.log(`Pricing snapshot: ${pricing.checked_at} (${pricing.source})`);
console.log(`Model: ${model.label} (${modelName})`);
const scenarioDescription = scenarioName === "custom" ? "custom token/request estimate" : scenario.description;
console.log(`Scenario: ${scenarioName} - ${scenarioDescription}`);
console.log(`Requests: ${requests}`);
console.log(`Per request tokens: input=${inputTokens}, cached_input=${cachedInputTokens}, output=${outputTokens}`);
console.log("");
console.log(`Input:        $${money(inputCost)}`);
console.log(`Cached input: $${money(cachedInputCost)}`);
console.log(`Output:       $${money(outputCost)}`);
console.log(`Total:        $${money(totalUsd)}`);
if (usdJpy !== undefined) {
  console.log(`Total JPY:    ${money(totalUsd * usdJpy, 3)} yen at ${usdJpy} JPY/USD`);
}
console.log("");
console.log("Note: default KoeCraft harness commands do not call OpenAI. This is only for opt-in live LLM tests.");

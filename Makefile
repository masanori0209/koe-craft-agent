.PHONY: agent-check agent-fixtures agent-dry-run asr-comparison-report live-asr-comparison-report rerender-live-asr-comparison-report recipe-dependency-audit agent-sim agent-sim3d agent-sim-time agent-sim-time3d agent-sim-fuzz agent-planner-recovery agent-state-from-scan mod-build mic-bridge-app mic-bridge-windows minecraft-smoke minecraft-recipes minecraft-recipe-catalog start-agent

agent-check:
	./scripts/harness/check-all.sh

agent-fixtures:
	./scripts/harness/fixtures.sh

agent-dry-run:
	./scripts/harness/dry-run.sh

asr-comparison-report:
	(cd mod && ./gradlew asrComparisonReport)

live-asr-comparison-report:
	(cd mod && ./gradlew liveAsrComparisonReport)

rerender-live-asr-comparison-report:
	(cd mod && ./gradlew rerenderLiveAsrComparisonReport)

recipe-dependency-audit:
	(cd mod && ./gradlew recipeDependencyAudit)

agent-sim:
	@echo "agent-sim was removed with the TypeScript agent. Use make agent-check."

agent-sim3d:
	@echo "agent-sim3d was removed with the TypeScript agent. Use make agent-check."

agent-sim-time:
	@echo "agent-sim-time was removed with the TypeScript agent. Use make agent-check."

agent-sim-time3d:
	@echo "agent-sim-time3d was removed with the TypeScript agent. Use make agent-check."

agent-sim-fuzz:
	@echo "agent-sim-fuzz was removed with the TypeScript agent. Use make agent-check."

agent-planner-recovery:
	$(MAKE) agent-fixtures

agent-state-from-scan:
	$(MAKE) agent-fixtures

mod-build:
	./scripts/harness/mod-build.sh

mic-bridge-app:
	./scripts/build-mic-bridge-app.sh

mic-bridge-windows:
	./scripts/build-mic-bridge-windows.sh

minecraft-smoke:
	npm run minecraft:smoke

minecraft-recipes:
	npm run minecraft:generate-vanilla-recipes

minecraft-recipe-catalog:
	npm run minecraft:validate-recipe-catalog

start-agent:
	./scripts/start-agent.sh

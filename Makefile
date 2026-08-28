.DEFAULT_GOAL := help

SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c

GRADLE ?= ./gradlew
REPO ?= senseFy/someday
REF ?= main
RUN_ID ?=
DEVICE ?=
LIMIT ?= 10
CHECK_WORKERS ?= 6
SMOKE_WORKERS ?= 2
RELEASE_READINESS_WORKERS ?= 2
SERVER_PORT ?= 3180
DB_PORT ?= 54329
SERVER_DB_URL ?= jdbc:postgresql://127.0.0.1:$(DB_PORT)/someday
SERVER_RELEASE_VERSION ?=
# Freeze raw command-line text before recipes receive it; never expand Make functions here.
override _SOMEDAY_SERVER_RELEASE_VERSION_ARG := $(value SERVER_RELEASE_VERSION)
unexport SERVER_RELEASE_VERSION
export _SOMEDAY_SERVER_RELEASE_VERSION_ARG
ANDROID_ARTIFACT_NAME ?= someday-android-debug-apk
ANDROID_ARTIFACT_DIR ?= artifacts/android-$(RUN_ID)
ANDROID_PLAY_AAB ?= app/android/build/outputs/bundle/release/android-release.aab
ANDROID_PLAY_PACKAGE_NAME ?= saien.someday
ANDROID_PLAY_TRACK ?= internal
ANDROID_PLAY_RELEASE_STATUS ?= completed
ANDROID_PLAY_RELEASE_NAME ?=
ANDROID_PLAY_SERVICE_ACCOUNT_JSON ?= $(GOOGLE_APPLICATION_CREDENTIALS)
export ANDROID_PLAY_SERVICE_ACCOUNT_JSON
ANDROID_PLAY_CONFIRM_PRODUCTION ?=
ANDROID_PLAY_DRY_RUN ?=

IOS_TEAM_ID ?=
IOS_BUILD_NUMBER ?=
IOS_MARKETING_VERSION ?=
IOS_ARCHIVE_PATH ?=
IOS_EXPORT_PATH ?=
APP_STORE_CONNECT_API_KEY_PATH ?=
APP_STORE_CONNECT_API_KEY_ID ?=
APP_STORE_CONNECT_API_ISSUER_ID ?=
IOS_PROVISIONING_PROFILE_SPECIFIER ?=
IOS_EXPORT_BUNDLE_ID ?=
IOS_SIGNING_CERTIFICATE ?=
IOS_UPLOAD_AUTH_MODE ?=
IOS_ALLOW_PROVISIONING_UPDATES ?= false
VERSION_NAME ?=

export IOS_TEAM_ID
export IOS_BUILD_NUMBER
export IOS_MARKETING_VERSION
export IOS_ARCHIVE_PATH
export IOS_EXPORT_PATH
export APP_STORE_CONNECT_API_KEY_PATH
export APP_STORE_CONNECT_API_KEY_ID
export APP_STORE_CONNECT_API_ISSUER_ID
export IOS_PROVISIONING_PROFILE_SPECIFIER
export IOS_EXPORT_BUNDLE_ID
export IOS_SIGNING_CERTIFICATE
export IOS_UPLOAD_AUTH_MODE
export IOS_ALLOW_PROVISIONING_UPDATES

IOS_RELEASE_SCRIPT := ./scripts/release-ios.sh
ANDROID_PLAY_VERIFY_SCRIPT := ./scripts/verify-play-aab.sh
ANDROID_PLAY_UPLOAD_SCRIPT := ./scripts/upload-google-play.sh
PUBLISH_TRACKS_SCRIPT := ./scripts/publish-tracks.sh
PUBLISH_TRACKS_TEST := ./scripts/tests/publish-tracks-test.sh
VERIFY_PLAY_AAB_TEST := ./scripts/tests/verify-play-aab-test.sh
APP_VERSION_ARGS := $(if $(VERSION_NAME),--version-name "$(VERSION_NAME)",)
MOBILE_RELEASE_ARGS ?=
MOBILE_RELEASE_LOG_ROOT ?=
ANDROID_PLAY_DRY_RUN_ENABLED := $(if $(filter yes y true 1,$(ANDROID_PLAY_DRY_RUN)),yes,)
ANDROID_PLAY_UPLOAD_ARGS = --aab "$(abspath $(ANDROID_PLAY_AAB))"
ANDROID_PLAY_UPLOAD_ARGS += --package-name "$(ANDROID_PLAY_PACKAGE_NAME)"
ANDROID_PLAY_UPLOAD_ARGS += --track "$(ANDROID_PLAY_TRACK)"
ANDROID_PLAY_UPLOAD_ARGS += --status "$(ANDROID_PLAY_RELEASE_STATUS)"
ANDROID_PLAY_UPLOAD_ARGS += $(if $(ANDROID_PLAY_RELEASE_NAME),--release-name "$(ANDROID_PLAY_RELEASE_NAME)",)
ANDROID_PLAY_UPLOAD_ARGS += $(if $(filter yes y true 1,$(ANDROID_PLAY_CONFIRM_PRODUCTION)),--confirm-production,)
ANDROID_PLAY_UPLOAD_ARGS += $(if $(ANDROID_PLAY_DRY_RUN_ENABLED),--dry-run,)

.PHONY: help
help: ## Show available commands
	@awk 'BEGIN {FS = ":.*##"; printf "Usage:\n  make <target>\n\n"} /^##@/ {printf "\n%s\n", substr($$0, 5)} /^[a-zA-Z0-9_.-]+:.*##/ {printf "  %-28s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ Setup
.PHONY: gradle-version setup
gradle-version: ## Print Gradle and toolchain version information
	$(GRADLE) --version

setup: ## Check Gradle and start local PostgreSQL
	$(GRADLE) --version
	docker compose up -d postgres

##@ Local services
.PHONY: services-up services-stop services-down services-health server-run server-health
services-up: ## Start the local PostgreSQL container
	docker compose up -d postgres

services-stop: ## Stop the local PostgreSQL container
	docker compose stop postgres

services-down: ## Remove local service containers and network
	docker compose down

services-health: ## Check local PostgreSQL health
	pg_isready -h 127.0.0.1 -p $(DB_PORT) -U someday

server-run: ## Run the local Ktor server
	SOMEDAY_PORT=$(SERVER_PORT) SOMEDAY_DB_URL=$(SERVER_DB_URL) $(GRADLE) :server:run

server-health: ## Check local Ktor server health
	curl -sf http://127.0.0.1:$(SERVER_PORT)/health

##@ Run apps
.PHONY: run run-desktop run-android run-ios
run: ## Start the interactive platform/device runner
	./scripts/run

run-desktop: ## Run the desktop app
	./scripts/run desktop

run-android: ## Install and launch Android debug app; optional DEVICE=<id>
	@if [[ -n "$(DEVICE)" ]]; then ./scripts/run android --device "$(DEVICE)"; else ./scripts/run android; fi

run-ios: ## Build, install, and launch iOS app; optional DEVICE=<id-or-name>
	@if [[ -n "$(DEVICE)" ]]; then ./scripts/run ios --device "$(DEVICE)"; else ./scripts/run ios; fi

##@ Android
.PHONY: android-debug android-install android-test android-smoke android-connected-test
.PHONY: android-release-play android-release-play-upload android-upload-play android-upload-check
.PHONY: build-android-play-aab check-release-control-options
.PHONY: check-android-play-release-env check-android-play-publisher-env
android-debug: ## Build Android debug APK
	$(GRADLE) :app:android:assembleDebug --dependency-verification=strict --stacktrace

android-install: ## Install Android debug APK on the selected adb device
	$(GRADLE) :app:android:installDebug --stacktrace

android-test: ## Run Android debug unit tests
	$(GRADLE) :app:android:testDebugUnitTest --stacktrace

android-smoke: ## Build Android shell and run host-side smoke tests
	$(GRADLE) :app:android:androidShellSmoke --stacktrace

android-connected-test: ## Run Android connected instrumentation tests
	$(GRADLE) :app:android:connectedDebugAndroidTest --max-workers=1

check-release-control-options:
	@if [[ "$(origin BUMP_VERSION)" != "undefined" || "$(origin IOS_BUMP_VERSION)" != "undefined" ]]; then \
		echo "ERROR: Release targets no longer accept BUMP_VERSION. Run bump-ios-version, bump-android-version, or bump-mobile-build-version explicitly."; \
		exit 2; \
	fi
	@if [[ "$(origin UPLOAD)" != "undefined" || "$(origin IOS_UPLOAD)" != "undefined" ]]; then \
		echo "ERROR: Release targets no longer accept UPLOAD. Unset it and choose an explicit upload target; run make help for the available targets."; \
		exit 2; \
	fi

check-android-play-release-env: check-release-control-options
	@test -n "$$SOMEDAY_ANDROID_KEYSTORE_PATH" || (echo "ERROR: SOMEDAY_ANDROID_KEYSTORE_PATH is required."; exit 1)
	@test -f "$$SOMEDAY_ANDROID_KEYSTORE_PATH" || (echo "ERROR: SOMEDAY_ANDROID_KEYSTORE_PATH does not point to a file."; exit 1)
	@test -n "$$SOMEDAY_ANDROID_KEYSTORE_PASSWORD" || (echo "ERROR: SOMEDAY_ANDROID_KEYSTORE_PASSWORD is required."; exit 1)
	@test -n "$$SOMEDAY_ANDROID_KEY_ALIAS" || (echo "ERROR: SOMEDAY_ANDROID_KEY_ALIAS is required."; exit 1)
	@test -n "$$SOMEDAY_ANDROID_KEY_PASSWORD" || (echo "ERROR: SOMEDAY_ANDROID_KEY_PASSWORD is required."; exit 1)

android-upload-check: check-android-play-release-env check-android-play-publisher-env ## Verify Play signing and upload credentials without building
	@echo "Android Play upload prerequisites are ready."

check-android-play-publisher-env: check-release-control-options
	@if [[ -z "$(ANDROID_PLAY_DRY_RUN_ENABLED)" ]]; then \
		if [[ -n "$$GOOGLE_PLAY_ACCESS_TOKEN" ]]; then \
			echo "Google Play authentication: GOOGLE_PLAY_ACCESS_TOKEN"; \
		elif [[ -z "$$ANDROID_PLAY_SERVICE_ACCOUNT_JSON" ]]; then \
			echo "ERROR: ANDROID_PLAY_SERVICE_ACCOUNT_JSON, GOOGLE_APPLICATION_CREDENTIALS, or GOOGLE_PLAY_ACCESS_TOKEN is required."; \
			exit 1; \
		elif [[ ! -f "$$ANDROID_PLAY_SERVICE_ACCOUNT_JSON" ]]; then \
			echo "ERROR: Configured Google Play service account JSON path does not point to a file."; \
			exit 1; \
		else \
			echo "Google Play service account JSON configured."; \
		fi; \
	fi

build-android-play-aab: check-android-play-release-env
	$(GRADLE) :app:android:bundleRelease --dependency-verification=strict --stacktrace
	@test -f "$(ANDROID_PLAY_AAB)" || (echo "ERROR: Google Play AAB was not produced: $(ANDROID_PLAY_AAB)"; exit 1)

android-release-play: build-android-play-aab ## Build and verify a signed Play AAB without uploading
	@ANDROID_PLAY_PACKAGE_NAME="$(ANDROID_PLAY_PACKAGE_NAME)" $(ANDROID_PLAY_VERIFY_SCRIPT) "$(abspath $(ANDROID_PLAY_AAB))"

android-release-play-upload: check-android-play-publisher-env build-android-play-aab ## Build, verify, and upload a signed AAB to Google Play
	@$(ANDROID_PLAY_UPLOAD_SCRIPT) $(ANDROID_PLAY_UPLOAD_ARGS)

android-upload-play: check-android-play-publisher-env ## Verify and upload an existing AAB to Google Play
	@$(ANDROID_PLAY_UPLOAD_SCRIPT) $(ANDROID_PLAY_UPLOAD_ARGS)

##@ iOS
.PHONY: ios-smoke ios-simulator-test ios-framework ios-framework-release check-ios-private-env check-ios-team
.PHONY: ios-release ios-upload-testflight ios-upload-archive ios-archive ios-export ios-upload-check
.PHONY: bump-ios-version bump-android-version bump-app-version bump-mobile-version bump-mobile-build-version
.PHONY: publish-tracks test-publish-tracks test-verify-play-aab
ios-smoke: ## Link iOS simulator framework and run iOS smoke tests
	$(GRADLE) :app:ios:iosShellSmoke --max-workers=1

ios-simulator-test: ## Run all iOS simulator unit tests
	$(GRADLE) iosSimulatorArm64Test --max-workers=1

ios-framework: ## Link the iOS simulator debug framework
	$(GRADLE) :app:ios:linkDebugFrameworkIosSimulatorArm64 --max-workers=1

ios-framework-release: ## Link the iOS device release framework
	SDK_NAME=iphoneos CONFIGURATION=Release GRADLEW="$(GRADLE)" SOMEDAY_IOS_GRADLE_MAX_WORKERS=2 \
		./scripts/build-ios-framework-for-xcode.sh

check-ios-private-env:
	@if [ "$(origin APP_STORE_CONNECT_API_KEY_PATH)" = "command line" ] || \
		[ "$(origin APP_STORE_CONNECT_API_KEY_ID)" = "command line" ] || \
		[ "$(origin APP_STORE_CONNECT_API_ISSUER_ID)" = "command line" ]; then \
		echo "ERROR: App Store Connect API credentials must be exported, not passed as make arguments."; \
		exit 1; \
	fi

check-ios-team: check-release-control-options check-ios-private-env ## Require IOS_TEAM_ID for App Store packaging targets
	@test -n "$$IOS_TEAM_ID" || (echo "ERROR: IOS_TEAM_ID must be exported for App Store packaging."; exit 1)

ios-upload-check: check-ios-team ## Verify iOS TestFlight upload credentials without building
	@echo "iOS TestFlight upload prerequisites are ready."

ios-release: check-ios-team ## Archive and export a local App Store Connect IPA
	@$(IOS_RELEASE_SCRIPT)

ios-upload-testflight: check-ios-team ## Archive and upload directly to TestFlight
	@$(IOS_RELEASE_SCRIPT) --upload

ios-upload-archive: check-ios-team ## Upload an existing archive to App Store Connect
	@$(IOS_RELEASE_SCRIPT) --upload --export-only

ios-archive: check-ios-team ## Create a signed Release archive only
	@$(IOS_RELEASE_SCRIPT) --archive-only

ios-export: check-ios-team ## Export an existing archive only
	@$(IOS_RELEASE_SCRIPT) --export-only

bump-ios-version: ## Bump iOS CURRENT_PROJECT_VERSION and commit
	./scripts/bump-mobile-version.sh --platform ios

bump-android-version: ## Bump Android versionCode and commit
	./scripts/bump-mobile-version.sh --platform android

bump-app-version: ## Align Android/iOS/Desktop visible versions and bump mobile code/build; optional VERSION_NAME=x.y.z
	./scripts/bump-mobile-version.sh --platform both --release $(APP_VERSION_ARGS)

bump-mobile-version: bump-app-version ## Compatibility alias for bump-app-version

bump-mobile-build-version: ## Bump iOS build number and Android versionCode only
	./scripts/bump-mobile-version.sh --platform both

publish-tracks: ## Prepare and publish Android internal and iOS TestFlight builds
	@SOMEDAY_RELEASE_LOG_ROOT="$(MOBILE_RELEASE_LOG_ROOT)" \
		SOMEDAY_ANDROID_TRACK=internal \
		$(PUBLISH_TRACKS_SCRIPT) $(MOBILE_RELEASE_ARGS)

test-publish-tracks: ## Verify the combined mobile test-track release workflow
	@$(PUBLISH_TRACKS_TEST)

test-verify-play-aab: ## Verify Play AAB native-alignment tool discovery
	@$(VERIFY_PLAY_AAB_TEST)

##@ Desktop
.PHONY: desktop-run desktop-smoke desktop-package-smoke desktop-release-macos desktop-test
desktop-run: ## Run the desktop app through Gradle
	$(GRADLE) :app:desktop:run

desktop-smoke: ## Run non-interactive desktop startup smoke
	$(GRADLE) :app:desktop:runUiSmoke --stacktrace

desktop-package-smoke: ## Run desktop Windows/Linux package-readiness smoke
	$(GRADLE) :app:desktop:desktopWindowsLinuxPackageSmoke --stacktrace

desktop-release-macos: ## Build macOS release DMG with developer options disabled
	GRADLEW="$(GRADLE)" ./scripts/package-macos-release

desktop-test: ## Run desktop JVM tests
	$(GRADLE) :app:desktop:jvmTest --stacktrace

##@ Server release
.PHONY: server-release server-release-plan server-release-status server-release-rehearse require-server-release-version
server-release: ## Open the interactive server release workflow; optional SERVER_RELEASE_VERSION=X.Y.Z
	@SERVER_RELEASE_VERSION="$$_SOMEDAY_SERVER_RELEASE_VERSION_ARG" ./scripts/server-release-tui

require-server-release-version:
	@if [[ -z "$${_SOMEDAY_SERVER_RELEASE_VERSION_ARG:-}" ]]; then \
		printf 'ERROR: SERVER_RELEASE_VERSION=X.Y.Z is required.\n' >&2; \
		exit 2; \
	fi

server-release-plan: require-server-release-version ## Print the server release plan; requires SERVER_RELEASE_VERSION=X.Y.Z
	@./scripts/server-release plan "$$_SOMEDAY_SERVER_RELEASE_VERSION_ARG"

server-release-status: require-server-release-version ## Inspect release state without changing it; requires SERVER_RELEASE_VERSION=X.Y.Z
	@./scripts/server-release status "$$_SOMEDAY_SERVER_RELEASE_VERSION_ARG"

server-release-rehearse: require-server-release-version ## Run the local server release rehearsal; requires SERVER_RELEASE_VERSION=X.Y.Z
	@./scripts/server-release rehearse "$$_SOMEDAY_SERVER_RELEASE_VERSION_ARG"

##@ Validation
.PHONY: lint compile check client-smoke shared-smoke server-test server-container-smoke integration-test real-remote-test sync-v3-gate sync-v3-apple-gate release-readiness validate android-lint
lint: ## Run source hygiene and Android lint
	$(GRADLE) sourceHygieneCheck :app:android:lintDebug

android-lint: ## Run Android lint
	$(GRADLE) :app:android:lintDebug --stacktrace

compile: ## Compile Kotlin metadata/JVM main and test sources
	$(GRADLE) compileKotlinMetadata compileKotlinJvm compileTestKotlinJvm --continue

check: ## Run full Gradle check
	$(GRADLE) check --max-workers=$(CHECK_WORKERS) --continue

client-smoke: ## Run Android/iOS/desktop platform smoke checks
	$(GRADLE) clientPlatformSmoke --max-workers=$(SMOKE_WORKERS)

shared-smoke: ## Run shared behavior tests across JVM/Android/iOS targets
	$(GRADLE) sharedBehaviorTargetSmoke --max-workers=$(SMOKE_WORKERS)

server-test: ## Run server unit and integration tests
	$(GRADLE) :server:test :server:integrationTest --max-workers=$(SMOKE_WORKERS)

server-container-smoke: ## Build and verify the production server image and Compose packaging
	./scripts/server-container-smoke

integration-test: ## Run hermetic repository topology tests
	$(GRADLE) :integration-tests:test --max-workers=$(SMOKE_WORKERS)

real-remote-test: ## Run live self-hosted tests; explicit SOMEDAY_* service environment is required
	$(GRADLE) :integration-tests:realRemoteTest --max-workers=$(SMOKE_WORKERS)

sync-v3-gate: ## Run the Linux/PostgreSQL/S3 System V3 release gate
	./scripts/sync-v3-reliability-gate

sync-v3-apple-gate: ## Run System V3 shared behavior and app shell tests on an iOS simulator
	./scripts/sync-v3-apple-gate

release-readiness: ## Resolve and compile unsigned Android/iOS/macOS release inputs with strict verification
	GRADLEW="$(GRADLE)" RELEASE_READINESS_WORKERS="$(RELEASE_READINESS_WORKERS)" ./scripts/verify-release-readiness

validate: ## Run final end-to-end platform validation
	$(GRADLE) endToEndPlatformValidation --max-workers=$(SMOKE_WORKERS)

##@ GitHub Actions
.PHONY: android-ci-trigger android-ci-list android-ci-watch android-ci-download
android-ci-trigger: ## Trigger Android GitHub Actions workflow; optional REF=<branch-or-tag>
	gh workflow run android.yml --repo "$(REPO)" --ref "$(REF)"

android-ci-list: ## List recent Android GitHub Actions runs
	gh run list --repo "$(REPO)" --workflow android.yml --limit "$(LIMIT)"

android-ci-watch: ## Watch an Android CI run; requires RUN_ID=<id>
	@test -n "$(RUN_ID)" || (echo "Usage: make android-ci-watch RUN_ID=<id>"; exit 1)
	gh run watch "$(RUN_ID)" --repo "$(REPO)" --exit-status

android-ci-download: ## Download Android CI artifact; requires RUN_ID=<id>
	@test -n "$(RUN_ID)" || (echo "Usage: make android-ci-download RUN_ID=<id>"; exit 1)
	gh run download "$(RUN_ID)" --repo "$(REPO)" --name "$(ANDROID_ARTIFACT_NAME)" --dir "$(ANDROID_ARTIFACT_DIR)"

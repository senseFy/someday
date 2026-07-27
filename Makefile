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
WEBDAV_PORT ?= 3182
SERVER_DB_URL ?= jdbc:postgresql://127.0.0.1:$(DB_PORT)/someday
ANDROID_ARTIFACT_NAME ?= someday-android-debug-apk
ANDROID_ARTIFACT_DIR ?= artifacts/android-$(RUN_ID)
UPLOAD ?=
ANDROID_PLAY_AAB ?= app/android/build/outputs/bundle/release/android-release.aab
ANDROID_PLAY_DEFAULT_SERVICE_ACCOUNT_JSON := $(HOME)/.config/saien/google-play-publisher.json
ANDROID_PLAY_PACKAGE_NAME ?= saien.someday
ANDROID_PLAY_TRACK ?= internal
ANDROID_PLAY_RELEASE_STATUS ?= completed
ANDROID_PLAY_RELEASE_NAME ?=
ANDROID_PLAY_SERVICE_ACCOUNT_JSON ?= $(or $(GOOGLE_PLAY_SERVICE_ACCOUNT_JSON),$(ANDROID_PLAY_DEFAULT_SERVICE_ACCOUNT_JSON))
ANDROID_PLAY_CONFIRM_PRODUCTION ?=
ANDROID_PLAY_DRY_RUN ?=

IOS_TEAM_ID ?=
IOS_BUILD_NUMBER ?=
IOS_MARKETING_VERSION ?=
IOS_BUMP_VERSION ?=
IOS_ARCHIVE_PATH ?=
IOS_EXPORT_PATH ?=
APP_STORE_CONNECT_APP_ID ?=
APP_STORE_CONNECT_API_KEY_PATH ?=
APP_STORE_CONNECT_API_KEY_ID ?=
APP_STORE_CONNECT_API_ISSUER_ID ?=
ASC_KEY_PATH ?=
ASC_KEY_ID ?=
ASC_ISSUER_ID ?=
EXPO_ASC_API_KEY_PATH ?=
EXPO_ASC_KEY_ID ?=
EXPO_ASC_ISSUER_ID ?=
IOS_PROVISIONING_PROFILE_SPECIFIER ?=
IOS_EXPORT_BUNDLE_ID ?=
IOS_SIGNING_CERTIFICATE ?=
IOS_UPLOAD_AUTH_MODE ?=
IOS_UPLOAD ?=
VERSION_NAME ?=

IOS_RELEASE_SCRIPT := ./scripts/release-ios.sh
ANDROID_PLAY_VERIFY_SCRIPT := ./scripts/verify-play-aab.sh
ANDROID_PLAY_UPLOAD_SCRIPT := ./scripts/upload-google-play.sh
APP_VERSION_ARGS := $(if $(VERSION_NAME),--version-name "$(VERSION_NAME)",)
IOS_ASC_KEY_PATH := $(or $(APP_STORE_CONNECT_API_KEY_PATH),$(ASC_KEY_PATH),$(EXPO_ASC_API_KEY_PATH))
IOS_ASC_KEY_ID := $(or $(APP_STORE_CONNECT_API_KEY_ID),$(ASC_KEY_ID),$(EXPO_ASC_KEY_ID))
IOS_ASC_ISSUER_ID := $(or $(APP_STORE_CONNECT_API_ISSUER_ID),$(ASC_ISSUER_ID),$(EXPO_ASC_ISSUER_ID))
IOS_RELEASE_ARGS := $(if $(IOS_TEAM_ID),--team "$(IOS_TEAM_ID)",)
IOS_RELEASE_ARGS += $(if $(IOS_BUILD_NUMBER),--build-number "$(IOS_BUILD_NUMBER)",)
IOS_RELEASE_ARGS += $(if $(IOS_MARKETING_VERSION),--version "$(IOS_MARKETING_VERSION)",)
IOS_RELEASE_ARGS += $(if $(filter yes y true 1,$(IOS_BUMP_VERSION)),--bump-version,)
IOS_RELEASE_ARGS += $(if $(filter no n false 0,$(IOS_BUMP_VERSION)),--no-bump-version,)
IOS_RELEASE_ARGS += $(if $(IOS_ARCHIVE_PATH),--archive-path "$(abspath $(IOS_ARCHIVE_PATH))",)
IOS_RELEASE_ARGS += $(if $(IOS_EXPORT_PATH),--export-path "$(abspath $(IOS_EXPORT_PATH))",)
IOS_RELEASE_ARGS += $(if $(APP_STORE_CONNECT_APP_ID),--app-store-connect-app-id "$(APP_STORE_CONNECT_APP_ID)",)
IOS_RELEASE_ARGS += $(if $(IOS_ASC_KEY_PATH),--auth-key-path "$(abspath $(IOS_ASC_KEY_PATH))",)
IOS_RELEASE_ARGS += $(if $(IOS_ASC_KEY_ID),--auth-key-id "$(IOS_ASC_KEY_ID)",)
IOS_RELEASE_ARGS += $(if $(IOS_ASC_ISSUER_ID),--auth-key-issuer-id "$(IOS_ASC_ISSUER_ID)",)
IOS_RELEASE_ARGS += $(if $(IOS_PROVISIONING_PROFILE_SPECIFIER),--provisioning-profile "$(IOS_PROVISIONING_PROFILE_SPECIFIER)",)
IOS_RELEASE_ARGS += $(if $(IOS_EXPORT_BUNDLE_ID),--bundle-id "$(IOS_EXPORT_BUNDLE_ID)",)
IOS_RELEASE_ARGS += $(if $(IOS_SIGNING_CERTIFICATE),--signing-certificate "$(IOS_SIGNING_CERTIFICATE)",)
IOS_RELEASE_ARGS += $(if $(IOS_UPLOAD_AUTH_MODE),--upload-auth-mode "$(IOS_UPLOAD_AUTH_MODE)",)
IOS_RELEASE_UPLOAD_ARG := $(if $(filter yes y true 1,$(IOS_UPLOAD)),--upload,)
UPLOAD_ENABLED := $(if $(filter yes y true 1,$(UPLOAD)),yes,)
ANDROID_PLAY_DRY_RUN_ENABLED := $(if $(filter yes y true 1,$(ANDROID_PLAY_DRY_RUN)),yes,)
ANDROID_PLAY_UPLOAD_ARGS = --aab "$(abspath $(ANDROID_PLAY_AAB))"
ANDROID_PLAY_UPLOAD_ARGS += --package-name "$(ANDROID_PLAY_PACKAGE_NAME)"
ANDROID_PLAY_UPLOAD_ARGS += --track "$(ANDROID_PLAY_TRACK)"
ANDROID_PLAY_UPLOAD_ARGS += --status "$(ANDROID_PLAY_RELEASE_STATUS)"
ANDROID_PLAY_UPLOAD_ARGS += $(if $(ANDROID_PLAY_RELEASE_NAME),--release-name "$(ANDROID_PLAY_RELEASE_NAME)",)
ANDROID_PLAY_UPLOAD_ARGS += $(if $(ANDROID_PLAY_SERVICE_ACCOUNT_JSON),--credentials "$(abspath $(ANDROID_PLAY_SERVICE_ACCOUNT_JSON))",)
ANDROID_PLAY_UPLOAD_ARGS += $(if $(filter yes y true 1,$(ANDROID_PLAY_CONFIRM_PRODUCTION)),--confirm-production,)
ANDROID_PLAY_UPLOAD_ARGS += $(if $(ANDROID_PLAY_DRY_RUN_ENABLED),--dry-run,)

.PHONY: help
help: ## Show available commands
	@awk 'BEGIN {FS = ":.*##"; printf "Usage:\n  make <target>\n\n"} /^##@/ {printf "\n%s\n", substr($$0, 5)} /^[a-zA-Z0-9_.-]+:.*##/ {printf "  %-28s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ Setup
.PHONY: gradle-version setup
gradle-version: ## Print Gradle and toolchain version information
	$(GRADLE) --version

setup: ## Check Gradle and start local PostgreSQL/WebDAV services
	$(GRADLE) --version
	docker compose up -d postgres webdav

##@ Local services
.PHONY: services-up services-stop services-down services-health server-run server-health
services-up: ## Start local PostgreSQL and WebDAV containers
	docker compose up -d postgres webdav

services-stop: ## Stop local PostgreSQL and WebDAV containers
	docker compose stop postgres webdav

services-down: ## Remove local service containers and network
	docker compose down

services-health: ## Check local PostgreSQL and WebDAV health
	pg_isready -h 127.0.0.1 -p $(DB_PORT) -U someday
	curl -sf -u someday:someday -X OPTIONS http://127.0.0.1:$(WEBDAV_PORT)/

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
.PHONY: android-release-play android-upload-play check-android-play-release-env
.PHONY: check-android-play-publisher-env check-android-play-release-upload-env
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

check-android-play-release-env:
	@test -n "$$SAIEN_KEYSTORE_PATH" || (echo "ERROR: SAIEN_KEYSTORE_PATH is required."; exit 1)
	@test -f "$$SAIEN_KEYSTORE_PATH" || (echo "ERROR: SAIEN_KEYSTORE_PATH does not point to a file."; exit 1)
	@test -n "$$SAIEN_KEYSTORE_PASSWORD" || (echo "ERROR: SAIEN_KEYSTORE_PASSWORD is required."; exit 1)
	@test -n "$$SAIEN_KEY_ALIAS" || (echo "ERROR: SAIEN_KEY_ALIAS is required."; exit 1)
	@test -n "$$SAIEN_KEY_PASSWORD" || (echo "ERROR: SAIEN_KEY_PASSWORD is required."; exit 1)

check-android-play-publisher-env:
	@if [[ -z "$(ANDROID_PLAY_DRY_RUN_ENABLED)" ]]; then \
		if [[ -n "$$GOOGLE_PLAY_ACCESS_TOKEN" ]]; then \
			echo "Google Play authentication: GOOGLE_PLAY_ACCESS_TOKEN"; \
		elif [[ -z "$(ANDROID_PLAY_SERVICE_ACCOUNT_JSON)" ]]; then \
			echo "ERROR: ANDROID_PLAY_SERVICE_ACCOUNT_JSON or GOOGLE_PLAY_ACCESS_TOKEN is required."; \
			exit 1; \
		elif [[ ! -f "$(abspath $(ANDROID_PLAY_SERVICE_ACCOUNT_JSON))" ]]; then \
			echo "ERROR: Google Play service account JSON not found: $(abspath $(ANDROID_PLAY_SERVICE_ACCOUNT_JSON))"; \
			exit 1; \
		else \
			echo "Google Play service account JSON: $(abspath $(ANDROID_PLAY_SERVICE_ACCOUNT_JSON))"; \
		fi; \
	fi

check-android-play-release-upload-env:
	@if [[ -n "$(UPLOAD_ENABLED)" ]]; then \
		$(MAKE) --no-print-directory check-android-play-publisher-env; \
	fi

android-release-play: check-android-play-release-env check-android-play-release-upload-env ## Build and verify a signed Play AAB; set UPLOAD=yes to upload
	$(GRADLE) :app:android:bundleRelease --dependency-verification=strict --stacktrace
	@test -f "$(ANDROID_PLAY_AAB)" || (echo "ERROR: Google Play AAB was not produced: $(ANDROID_PLAY_AAB)"; exit 1)
	@if [[ -n "$(UPLOAD_ENABLED)" ]]; then \
		$(ANDROID_PLAY_UPLOAD_SCRIPT) $(ANDROID_PLAY_UPLOAD_ARGS); \
	else \
		ANDROID_PLAY_PACKAGE_NAME="$(ANDROID_PLAY_PACKAGE_NAME)" $(ANDROID_PLAY_VERIFY_SCRIPT) "$(abspath $(ANDROID_PLAY_AAB))"; \
		echo "Google Play upload skipped. Re-run with UPLOAD=yes to upload."; \
	fi

android-upload-play: check-android-play-publisher-env ## Verify and upload an existing AAB to Google Play
	$(ANDROID_PLAY_UPLOAD_SCRIPT) $(ANDROID_PLAY_UPLOAD_ARGS)

##@ iOS
.PHONY: ios-smoke ios-simulator-test ios-framework ios-framework-release check-ios-team
.PHONY: ios-release ios-upload-testflight ios-upload-archive ios-archive ios-export
.PHONY: bump-ios-version bump-android-version bump-app-version bump-mobile-version bump-mobile-build-version
ios-smoke: ## Link iOS simulator framework and run iOS smoke tests
	$(GRADLE) :app:ios:iosShellSmoke --max-workers=1

ios-simulator-test: ## Run all iOS simulator unit tests
	$(GRADLE) iosSimulatorArm64Test --max-workers=1

ios-framework: ## Link the iOS simulator debug framework
	$(GRADLE) :app:ios:linkDebugFrameworkIosSimulatorArm64 --max-workers=1

ios-framework-release: ## Link the iOS device release framework with developer options and V2 activation disabled
	SDK_NAME=iphoneos CONFIGURATION=Release GRADLEW="$(GRADLE)" SOMEDAY_IOS_GRADLE_MAX_WORKERS=2 \
		./scripts/build-ios-framework-for-xcode.sh

check-ios-team: ## Require IOS_TEAM_ID for App Store packaging targets
	@test -n "$(IOS_TEAM_ID)" || (echo "ERROR: IOS_TEAM_ID is required. Example: make ios-release IOS_TEAM_ID=YOUR_TEAM_ID"; exit 1)

ios-release: check-ios-team ## Archive and export a local App Store Connect IPA; set IOS_UPLOAD=yes to upload
	$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) $(IOS_RELEASE_UPLOAD_ARG)

ios-upload-testflight: check-ios-team ## Archive and upload directly to TestFlight
	$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --upload

ios-upload-archive: check-ios-team ## Upload an existing archive to App Store Connect
	$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --upload --export-only

ios-archive: check-ios-team ## Create a signed Release archive only
	$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --archive-only

ios-export: check-ios-team ## Export an existing archive only
	$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --export-only

bump-ios-version: ## Bump iOS CURRENT_PROJECT_VERSION and commit
	./scripts/bump-mobile-version.sh --platform ios

bump-android-version: ## Bump Android versionCode and commit
	./scripts/bump-mobile-version.sh --platform android

bump-app-version: ## Align Android/iOS/Desktop visible versions and bump mobile code/build; optional VERSION_NAME=x.y.z
	./scripts/bump-mobile-version.sh --platform both --release $(APP_VERSION_ARGS)

bump-mobile-version: bump-app-version ## Compatibility alias for bump-app-version

bump-mobile-build-version: ## Bump iOS build number and Android versionCode only
	./scripts/bump-mobile-version.sh --platform both

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

##@ Validation
.PHONY: lint compile check client-smoke shared-smoke server-test integration-test real-remote-test sync-v2-gate release-readiness validate android-lint
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

integration-test: ## Run hermetic repository topology tests
	$(GRADLE) :integration-tests:test --max-workers=$(SMOKE_WORKERS)

real-remote-test: ## Run live WebDAV/self-hosted tests; explicit SOMEDAY_* service environment is required
	$(GRADLE) :integration-tests:realRemoteTest --max-workers=$(SMOKE_WORKERS)

sync-v2-gate: ## Run the Sync V2 reliability gate (open-source baseline)
	./scripts/sync-v2-reliability-gate

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

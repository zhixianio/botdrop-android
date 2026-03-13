# U2 Apt Install Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Switch Android-side uiautomator2 setup from pip/GitHub installation to the BotDrop apt package `python-uiautomator2-botdrop`.

**Architecture:** Keep the existing multi-step setup flow in `AutomationPanelActivity`, but change the generated Termux shell commands so step 2 prepares apt dependencies and step 3 verifies/installs the apt package instead of pip packages. Add focused Robolectric coverage around the private command builders so regressions are caught without needing device-side integration tests.

**Tech Stack:** Android Java, Robolectric, Gradle unit tests, Termux shell command generation.

---

### Task 1: Add failing coverage for apt-based command generation

**Files:**
- Create: `app/src/test/java/app/botdrop/AutomationPanelActivityCommandTest.java`
- Modify: `app/src/main/java/app/botdrop/AutomationPanelActivity.java`

**Step 1: Write the failing test**

Add Robolectric tests that instantiate `AutomationPanelActivity`, invoke the private command builders via reflection, and assert:
- dependency reinstall command no longer references `python-pip` or pip availability checks
- installed check uses `dpkg -s python-uiautomator2-botdrop`
- install command uses `apt install -y python-uiautomator2-botdrop`
- generated commands no longer contain `pip show`, `pip3 show`, or `git+https://github.com/lay2dev/uiautomator2.git`

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.botdrop.AutomationPanelActivityCommandTest`
Expected: FAIL because current commands still use pip/GitHub install logic.

### Task 2: Update the setup commands to use apt

**Files:**
- Modify: `app/src/main/java/app/botdrop/AutomationPanelActivity.java`
- Modify: `app/src/main/assets/skills/botdrop-automation/SKILL.md`

**Step 1: Write minimal implementation**

Update the command builders so:
- reinstall step keeps apt-based environment prep but drops the old pip requirement
- installed check reads package state from dpkg/apt instead of pip
- install step installs the apt package directly
- user-facing skill instructions match the new apt-first install flow

**Step 2: Run focused tests**

Run: `./gradlew :app:testDebugUnitTest --tests app.botdrop.AutomationPanelActivityCommandTest`
Expected: PASS

### Task 3: Run targeted regression verification

**Files:**
- No code changes expected

**Step 1: Run relevant existing tests**

Run: `./gradlew :app:testDebugUnitTest --tests app.botdrop.OpenclawVersionUtilsTest --tests app.botdrop.BotDropServiceTest --tests app.botdrop.AutomationPanelActivityCommandTest`
Expected: PASS

**Step 2: Inspect resulting diff**

Run: `git diff -- app/src/main/java/app/botdrop/AutomationPanelActivity.java app/src/main/assets/skills/botdrop-automation/SKILL.md app/src/test/java/app/botdrop/AutomationPanelActivityCommandTest.java docs/plans/2026-03-13-u2-apt-install.md`
Expected: only pip-to-apt install flow changes plus new test/plan.

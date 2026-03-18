# OpenClaw Log Scroll Refresh Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep the OpenClaw log dialog anchored when logs refresh so users can continuously read new output at the bottom without the view jumping to the top.

**Architecture:** Preserve and restore the `ScrollView` position inside each log page during text updates. If the user was already at the bottom before refresh, snap back to the bottom after the new text is applied; otherwise restore the previous `scrollY` so mid-log reading is not interrupted.

**Tech Stack:** Android Views, Java, Robolectric, JUnit 4

---

### Task 1: Add a failing regression test for log page refresh behavior

**Files:**
- Create: `app/src/test/java/app/botdrop/OpenclawLogScrollBehaviorTest.java`
- Reference: `app/src/main/res/layout/item_openclaw_log_page.xml`

**Step 1: Write the failing test**

Create a Robolectric test that inflates the log page layout, scrolls the `ScrollView` to the bottom, updates the log text through the production binding path, and asserts the view remains anchored near the bottom after refresh.

Add a second test that scrolls to a middle offset, refreshes the text, and asserts the offset is preserved instead of jumping back to `0`.

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.botdrop.OpenclawLogScrollBehaviorTest`

Expected: FAIL because the current refresh path resets the `ScrollView` position during `TextView#setText()`.

### Task 2: Implement the minimal fix in the log page binding path

**Files:**
- Modify: `app/src/main/java/app/botdrop/DashboardActivity.java`

**Step 1: Add scroll-preserving update logic**

Update the log page holder to keep a reference to the page `ScrollView`, detect whether it is currently at the bottom, and restore either bottom anchoring or the prior `scrollY` after applying the refreshed text.

**Step 2: Keep the fix local**

Do not change polling cadence, dialog lifecycle, or command execution. Limit the change to how refreshed text is rebound into the existing page view.

**Step 3: Run targeted tests**

Run: `./gradlew :app:testDebugUnitTest --tests app.botdrop.OpenclawLogScrollBehaviorTest`

Expected: PASS

### Task 3: Verify no regression in the app test suite

**Files:**
- Modify: `app/src/main/java/app/botdrop/DashboardActivity.java`
- Create: `app/src/test/java/app/botdrop/OpenclawLogScrollBehaviorTest.java`

**Step 1: Run broader verification**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS

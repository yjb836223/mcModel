# Task 1 Brief: Settings + Process Interfaces

## Context
Baritone 1.21.8 Fabric mod (Java 21). Adding two new features controlled by settings.
Working directory: C:\Users\jibiyang\Downloads\baritone-1.19.4

## Global Constraints
- Java 21 syntax only
- All new files in baritone.* packages
- Settings default to false (opt-in)
- Never remove or break existing settings/commands

## What To Do

### Step 1: Modify src/api/java/baritone/api/Settings.java

Find the `itemSaverThreshold` setting (around line 881). Add these two settings DIRECTLY AFTER it:

```java
/**
 * When enabled, pauses #mine when ALL pickaxes are low-durability and repairs them via Mending XP.
 */
public final Setting<Boolean> mindfix = new Setting<>(false);

/**
 * When enabled, compresses mining loot into shulker boxes when inventory is full.
 */
public final Setting<Boolean> fullbag = new Setting<>(false);
```

### Step 2: Create src/api/java/baritone/api/process/IMindFixProcess.java

```java
package baritone.api.process;

public interface IMindFixProcess extends IBaritoneProcess {
    // Enabled/disabled via Baritone.settings().mindfix
}
```

### Step 3: Create src/api/java/baritone/api/process/IFullBagProcess.java

```java
package baritone.api.process;

public interface IFullBagProcess extends IBaritoneProcess {
    // Enabled/disabled via Baritone.settings().fullbag
}
```

### Step 4: Verify compilation

Run: `.\gradlew :fabric:compileJava`
Expected: BUILD SUCCESSFUL (warnings OK, errors NOT OK)

### Step 5: Commit

Stage and commit the 3 changed/created files with message:
"feat: add mindfix and fullbag settings and process interfaces"

## Report File
Write your full report to: C:\Users\jibiyang\Downloads\baritone-1.19.4\.superpowers\sdd\task-1-report.md

## Report Format
End your response with ONE of these status lines:
- DONE
- DONE_WITH_CONCERNS
- NEEDS_CONTEXT
- BLOCKED

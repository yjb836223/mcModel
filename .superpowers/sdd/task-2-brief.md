# Task 2 Brief: IBaritone getters + MineProcess accessors

## Context
Baritone 1.21.8 Fabric mod. Task 1 already added IMindFixProcess and IFullBagProcess interfaces.
Working directory: C:\Users\jibiyang\Downloads\baritone-1.19.4

## Global Constraints
- Java 21 syntax only
- Never remove or break existing code

## What To Do

### Step 1: Modify src/api/java/baritone/api/IBaritone.java

Find the line `IMineProcess getMineProcess();` and add these two methods directly after it:

```java
IMindFixProcess getMindFixProcess();

IFullBagProcess getFullBagProcess();
```

Note: IMindFixProcess and IFullBagProcess are already in package baritone.api.process — check if IBaritone.java already imports that package or uses wildcard imports, and add imports if needed.

### Step 2: Modify src/main/java/baritone/process/MineProcess.java

Read the file. Find the private field declarations section (filter, knownOreLocations, etc.).
After the existing field declarations, add two package-private getter methods:

```java
// Package-private: used by MindFixProcess and FullBagProcess
BlockOptionalMetaLookup getFilter() {
    return filter;
}

int getDesiredQuantity() {
    return desiredQuantity;
}
```

BlockOptionalMetaLookup is already imported in MineProcess.java.

### Step 3: Verify compilation

Run: `.\gradlew :fabric:compileJava`
Wait for it to complete fully. Expected: BUILD SUCCESSFUL

### Step 4: Commit

Stage and commit the 2 modified files with message:
"feat: add IMindFix/IFullBag getters to IBaritone and package-private accessors to MineProcess"

## Report File
Write your full report to: C:\Users\jibiyang\Downloads\baritone-1.19.4\.superpowers\sdd\task-2-report.md

## Report Format
End your response with ONE status word: DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, or BLOCKED

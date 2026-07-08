# Task 3 Brief: Commands

## Context
Baritone 1.21.8 Fabric mod. Tasks 1 & 2 are complete.
Working directory: C:\Users\jibiyang\Downloads\baritone-1.19.4

## Global Constraints
- Java 21 syntax only
- All new files in baritone.* packages
- Never remove or break existing code

## What To Do

### Step 1: Create src/main/java/baritone/command/defaults/MindFixCommand.java

Look at an existing simple command for reference (e.g. src/main/java/baritone/command/defaults/FarmCommand.java or similar) to confirm the exact import paths and Command base class pattern.

Create the file with this exact content:

```java
package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.Baritone;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MindFixCommand extends Command {

    public MindFixCommand(IBaritone baritone) {
        super(baritone, "mindfix");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        boolean enable = args.getAs(Boolean.class);
        Baritone.settings().mindfix.value = enable;
        logDirect("mindfix " + (enable ? "已开启" : "已关闭"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteAs(Boolean.class);
    }

    @Override
    public String getShortDesc() {
        return "自动修复稿子耐久 (需要经验修补附魔)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
            "当所有稿子耐久均低于阈值时，暂停挖矿并挖 XP 矿修复。",
            "用法: #mindfix true/false"
        );
    }
}
```

### Step 2: Create src/main/java/baritone/command/defaults/FullBagCommand.java

```java
package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.Baritone;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class FullBagCommand extends Command {

    public FullBagCommand(IBaritone baritone) {
        super(baritone, "fullbag");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        boolean enable = args.getAs(Boolean.class);
        Baritone.settings().fullbag.value = enable;
        logDirect("fullbag " + (enable ? "已开启" : "已关闭"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteAs(Boolean.class);
    }

    @Override
    public String getShortDesc() {
        return "背包满时自动压缩到潜影盒";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
            "背包满时将挖矿产物存入潜影盒，腾出空间继续挖矿。",
            "用法: #fullbag true/false"
        );
    }
}
```

IMPORTANT: Before writing these files, read one existing command (e.g. MineCommand.java or FarmCommand.java) to verify that:
- The import for Command is correct
- args.getAs(Boolean.class) is the right API (or if it differs, adjust accordingly)
- args.requireExactly(1) is the right API
- logDirect() is available from the Command base class

Adjust imports/method calls if the actual API differs from the plan.

### Step 3: Verify compilation

Run PowerShell command and WAIT for full completion:
```
.\gradlew :fabric:compileJava
```
Wait until you see BUILD SUCCESSFUL or BUILD FAILED. Do not return until the build finishes.

Note: If Gradle daemon gets killed (NEXTHINK security software), retry with:
```
.\gradlew :fabric:compileJava --no-daemon
```

### Step 4: Commit

Stage both new files and commit with message:
"feat: add MindFixCommand and FullBagCommand"

## Report File
Write your full report to: C:\Users\jibiyang\Downloads\baritone-1.19.4\.superpowers\sdd\task-3-report.md

Include: files created, any API adjustments made, compilation result, commit hash.

## Report Format
End your response with ONE status word: DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, or BLOCKED

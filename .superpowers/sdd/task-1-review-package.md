# Task 1 Review Package

## Commits (9e86d5f → f04b145)
f04b145 feat: add mindfix and fullbag settings and process interfaces

## Stat
 src/api/java/baritone/api/Settings.java             | 10 ++++++++++
 src/api/java/baritone/api/process/IFullBagProcess.java |  5 +++++
 src/api/java/baritone/api/process/IMindFixProcess.java |  5 +++++
 3 files changed, 20 insertions(+)

## Full Diff

```diff
diff --git a/src/api/java/baritone/api/Settings.java b/src/api/java/baritone/api/Settings.java
index 3fd8ab1..fdbcef0 100644
--- a/src/api/java/baritone/api/Settings.java
+++ b/src/api/java/baritone/api/Settings.java
@@ -880,6 +880,16 @@ public final class Settings {
      */
     public final Setting<Integer> itemSaverThreshold = new Setting<>(10);
 
+    /**
+     * When enabled, pauses #mine when ALL pickaxes are low-durability and repairs them via Mending XP.
+     */
+    public final Setting<Boolean> mindfix = new Setting<>(false);
+
+    /**
+     * When enabled, compresses mining loot into shulker boxes when inventory is full.
+     */
+    public final Setting<Boolean> fullbag = new Setting<>(false);
+
     /**
      * Always prefer silk touch tools over regular tools. This will not sacrifice speed, but it will always prefer silk
      * touch tools over other tools of the same speed. This includes always choosing ANY silk touch tool over your hand.
diff --git a/src/api/java/baritone/api/process/IFullBagProcess.java b/src/api/java/baritone/api/process/IFullBagProcess.java
new file mode 100644
index 0000000..d5140ae
--- /dev/null
+++ b/src/api/java/baritone/api/process/IFullBagProcess.java
@@ -0,0 +1,5 @@
+package baritone.api.process;
+
+public interface IFullBagProcess extends IBaritoneProcess {
+    // Enabled/disabled via Baritone.settings().fullbag
+}
diff --git a/src/api/java/baritone/api/process/IMindFixProcess.java b/src/api/java/baritone/api/process/IMindFixProcess.java
new file mode 100644
index 0000000..e424fa2
--- /dev/null
+++ b/src/api/java/baritone/api/process/IMindFixProcess.java
@@ -0,0 +1,5 @@
+package baritone.api.process;

+public interface IMindFixProcess extends IBaritoneProcess {
+    // Enabled/disabled via Baritone.settings().mindfix
+}
```

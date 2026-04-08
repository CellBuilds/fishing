# Skyblock Fishing — Meteor Client Addon

A Meteor Client addon for Skyblock servers with two features built into one module.

---

## Installing into Prism Launcher

You do **not** run this like a standalone program. You compile it to a `.jar` and drop it
into the same `mods` folder as your Meteor Client install. Here's the full process:

### Step 1 — Install prerequisites (one time only)
- [JDK 21](https://adoptium.net/) — needed to compile
- [Git](https://git-scm.com/) (optional, or just download the zip)

### Step 2 — Build the jar
```
# In a terminal / command prompt:
cd skyblock-fishing-addon
gradlew.bat build          # Windows
./gradlew build            # Mac / Linux
```
Output jar: `build/libs/skyblock-fishing-1.0.0.jar`

### Step 3 — Add to Prism
1. Open Prism Launcher
2. Right-click your instance → **Edit** → **Mods**
3. Click **Add File** (top right of the screenshot you shared)
4. Select `skyblock-fishing-1.0.0.jar`
5. Launch — the addon appears under **Skyblock Fishing** in Meteor's module list

> Your instance already has Fabric API + Meteor Client installed, so it will load correctly.

---

## Features

### Module: `skyblock-auto-fish`
Found in Meteor GUI → **Skyblock Fishing** category.

**AutoFish** — watches the bobber Y-velocity dip and the `entity.fishing_bobber.splash`
sound to automatically reel in and recast.

**Turbo Fishing** — listens to every chat/title/subtitle/action-bar packet for the string:
```
Turbo Fishing has activated
```
When matched, it sends `INTERACT_ITEM` right-click packets in bursts for **20 seconds**
(configurable), then stops automatically — no deactivation message needed.

| Setting | Default | What it does |
|---|---|---|
| `recast-delay-ms` | 300 | Wait after reel-in before recasting |
| `motion-threshold` | 0.15 | Y-velocity dip that triggers reel-in |
| `swing-arm` | off | Send arm swing animation |
| `turbo-packets-per-burst` | 2 | Packets per burst (matches PacketSender) |
| `turbo-interval-ticks` | 1 | Ticks between bursts |
| `turbo-duration-seconds` | 20 | How long the enchant lasts |
| `turbo-activate-text` | `Turbo Fishing has activated` | Trigger substring |
| `debug-log` | off | Print every incoming message to logs |

### HUD: `fishing-counter`
Enable in Meteor GUI → **HUD** tab. Shows top-right (draggable):
```
FISHING
────────────────────
Fish Caught:   1773
Turbo Procs:   45
XP Gained:     1.5K
Money Gained:  85.7K
Turbo Active:  YES (18s) / NO
```
Every 60 seconds the addon sends `/bal` and tracks your money delta.

---

## Adjusting the trigger string

If detection isn't firing, enable `debug-log` in the module settings. Every incoming
message will be printed to the Minecraft log file at:
```
%appdata%\.minecraft\logs\latest.log   (Windows)
~/.minecraft/logs/latest.log           (Linux/Mac)
```
Find the exact text the server sends and paste it into `turbo-activate-text`.

---

## License
GPL-3.0 — same as Meteor Client.

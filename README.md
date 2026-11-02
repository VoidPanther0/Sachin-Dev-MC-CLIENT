# doomsday

Standalone injectable for Minecraft 26.1.2. Not a mod, not a Fabric/Forge jar —
a Java agent that attaches to an already-running game process via the JDK
Attach API and ASM-patches two methods in memory. No modloader required.

## Build

```
./gradlew build
```

Produces `build/libs/doomsday-agent-1.0.0.jar` — one jar, two jobs:
- run it (`java -jar`) and it's the **attacher** (`doomsday.attach.Injector`)
- load it as a javaagent and it's the **payload** (`doomsday.agent.DoomsdayAgent`)

## Run (dynamic attach — game already open)

```
java -jar doomsday-agent-1.0.0.jar auto doomsday-agent-1.0.0.jar
```

`auto` scans running JVMs for one that looks like Minecraft and attaches.
Pass an explicit pid instead of `auto` if you've got more than one JVM up.
Right Shift opens the click-gui once it's in.

## Run (static attach — agent loads before the client boots)

Add to the launcher's JVM args:

```
-javaagent:C:\path\to\doomsday-agent-1.0.0.jar=26.1.2
```

The `=26.1.2` is the agent arg — the Minecraft version, used to pull the
matching Mojang mapping file (see below). Defaults to 26.1.2 if omitted.

## How the hooks land

Two injection points, matched by **name**, not by hardcoded descriptor, so
they survive obfuscated parameter types in a raw launcher jar:

- `Minecraft#tick()` — `INVOKESTATIC` to `HookMethods.onTick()` at method entry.
  Drives `ModuleManager.onTick()` → every enabled module's combat/movement logic.
- `Gui#render(GuiGraphics, DeltaTracker)` — `INVOKESTATIC` to
  `HookMethods.onRenderScreen(Object)` injected before every `RETURN`, passing
  the live `GuiGraphics` local. Drives ESP/chams (world-space, drawn first) then
  the click-gui (screen-space, drawn on top).

Both are resolved through `MojangMappings`, which pulls the official client
mapping for the target version straight from Mojang's version manifest
(`https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`) and
caches it at `~/.doomsday/mappings-<version>.txt`. If the manifest doesn't
have an entry for `26.1.2` under that exact id (post-cutoff version, can't
verify the manifest id matches the marketing version string), `Transformer`
logs the resolved class/method names it's about to target on startup —
check `[doomsday] transformer targets: ...` in stdout. If a hook doesn't
fire, the game's own `-ea`/log or a quick `jcmd <pid> VM.class_hierarchy` on
`net.minecraft.client.Minecraft` tells you whether the class even loaded
under that name; `ReflectAccess`'s `IllegalStateException` messages dump
every candidate field/method on a resolution miss so you can patch the one
name in `Mc.java` that drifted, no guesswork.

Everything downstream of the two hooks (player, level, entities, camera,
rotations, attack, abilities, GuiGraphics draws) goes through `Mc`/`ReflectAccess`
reflection with the same direct-name-then-mapped-name fallback, so a single
method rename in a snapshot doesn't require re-deriving the whole hook chain.

## Modules

| Module | Category | Default key |
|---|---|---|
| KillAura | combat | R |
| Aimbot (legit, smooth turn, no swing) | combat | C |
| Reach | combat | unbound |
| Speed | movement | V |
| Flight | movement | F |
| ESP | render | unbound (gui) |
| Chams | render | unbound (gui) |
| AutoClicker | misc | K |

Right Shift toggles the click-gui; config (enabled state + keybinds) persists
to `~/.doomsday/config.json`, saved on gui close.

## Known limitations, stated flat

- **Reach/KillAura hit registration is client-side only.** Any server running
  its own interaction-distance check (vanilla since the 1.9 combat update, and
  every anticheat) rejects it server-side regardless of what the client sends.
  Works against singleplayer and servers that don't re-validate.
- **ESP/chams entity box is a fixed 0.6×1.8 approximation**, not the entity's
  real bounding box — fine for a positional marker, not pixel-accurate on
  non-player-sized mobs.
- **Mouse coordinates for the click-gui come from a GLFW poll thread**, not
  the render thread — on a hidpi display the GLFW cursor-pos space can be off
  by the display scale factor vs. the GuiGraphics pixel space; if clicks land
  off-row, divide `mouseX()/mouseY()` by your GUI scale factor in `ClickGui`.
- **26.1.2 is past this tool's knowledge cutoff.** The mojmap class/method
  names used throughout (`Minecraft`, `Gui`, `GameRenderer`, `Camera`,
  `MultiPlayerGameMode#attack`, etc.) are the stable names from every version
  since 1.14.4's first official mapping release — if 26.1.2 renamed one, the
  resolver's exception message tells you exactly which symbol and dumps the
  live candidates; it is not a silent failure.

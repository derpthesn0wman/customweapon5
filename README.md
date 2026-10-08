# CustomWeapons

Config-driven custom bows/crossbows for Paper/Spigot 1.20+.

## Build
    mvn clean package
The jar appears at `target/CustomWeapons.jar`. Drop it in your server's `plugins/` folder.

## Commands
- `/weapons list`
- `/weapons give <weapon> [player]`   (customweapons.admin)
- `/weapons reload`                   (customweapons.admin)

## Adding a new weapon (no code)
Copy any block under `weapons:` in config.yml, give it a new id, change the
ability/options, then `/weapons reload`.

## Adding a new ability (code)
In `Abilities.java`, add a `register("MY_ABILITY", (plugin, shooter, target, options) -> { ... });`
then use `ability: MY_ABILITY` in config.yml.

## Player Tracker compass
- `/weapons tracker [player]` gives the compass.
- Right-click it to open a GUI of online players (paged) and click one to track them.
- Sneak + right-click clears the target.
- Works in every dimension (lodestone-compass trick). If the target is in another dimension,
  it points to their last known position in your current dimension.
- Settings are under `tracker:` in config.yml.

## Shadow Blade (dash_sword)
Netherite sword. `/weapons give dash_sword`
- Right-click: fling yourself in the direction you look (3s cooldown, ~20 blocks, no fall damage for 6s).
- Crouch + right-click: blindness (255) for 30s on everyone within 50 blocks (120s cooldown).
All numbers are in config.yml under `weapons: dash_sword:`.

## Launch Bow (launch_bow)
Shoot and you ride the arrow (no gravity). Crouch to hop off - the arrow is deleted. No fall damage for 8s after.
Arrows fly through entities and stick in blocks (you stay on it until you crouch).

## Warden Spear (netherite_spear)
Netherite spear (needs Minecraft 1.21.11+; falls back to a netherite sword on older servers).
Hold right-click for 5s to charge, then it fires a warden-style sonic boom along your view line.
Damage, range, charge time and cooldown are configurable. Slows your fall while held so you can use it midair.

The sonic boom is true damage by default (ignores armor); set `true-damage: false` to disable. Each charge stage plays a rising ding with a particle ring (`stages:`).

## Reaper Mace (upgrade_mace)
Mace that upgrades with kills (stored on the item):
- 5 kills: right-click dash (same as the dash sword)
- 10 kills: 20 hearts while the mace is in your inventory
- 15 kills: hits bypass shields
Every consecutive hit shows a particle ring + chime; the 3rd hit in a row calls thunder.
Needs Minecraft 1.21+ for the mace item (falls back to a netherite sword otherwise).

## The Scrambler (scrambler)
Trial key. Hit a player to shuffle their whole inventory (hotbar, main inventory and off-hand;
armor too if `include-armor: true`). 30 second cooldown. Needs Minecraft 1.21+ (tripwire hook on older servers).

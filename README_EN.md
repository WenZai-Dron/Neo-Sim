![neo_sim_logo.png](src/main/resources/assets/neo_sim/neo_sim_logo.png)

# Neo-Sim · New Sim City

[中文](README.md) | **English**

## **_Everything Is Simulated_**

A city-simulation mod built on **NeoForge 1.21.1**, a tribute to — and a continuation of — [Sim-U-Kraft](https://www.mcmod.cn/class/489.html), with support for [Litematica](https://www.mcmod.cn/class/2261.html) schematic formats.

For the algorithmic work behind it, see [算法改进](算法改进.md) (Chinese).

---

## Features

### Signature Blocks

- **Delivery Box** `delivery_box`: When a build site runs short of materials, a courier fetches them from the station chest, walks them to the site, and pays on drop-off
- **Rebuild Box** `rebuild_box`: Place it next to a building's Control Box; it reads the adjacent chests for materials and automatically restores any missing blocks; **no architect needed**

### Automated Construction

- Keeps all **155 legacy Sim-U-Kraft community buildings**
- Imports custom buildings from **Litematica** files

### Automated Production

- **Workboxes** do the work for you: farming, mining, delivery, and land clearing
- **Automatic mod block / crop compatibility**: the registry of installed mods is scanned, so mod crops automatically feed into the farming box and are written to `NeoSim/Json/compat/crops.json`; dependent blocks (torches, ladders, hanging blocks, and mod blocks that override `canSurvive` / `getStateForPlacement`) are placed in the second round - one click adds a block to the attached-block list, while `modded_blocks.json` tunes mod blocks per block or per namespace

### NPC

- A wide variety of citizen appearances, with support for imported custom skins
- Custom citizen names

### Building Preview

- Translucent blueprint hologram with free **move / rotate / mirror** adjustment and a free-flying bird's-eye view
- **Cached** rendering keeps even the largest builds at full frame rate

### Cities and Simulation

- Players found or join a city, and citizens live their lives within it
- **Live HUD** with missing-material alerts and city-wide completion announcements

### UI Customization

- **HUD your way**: size, position, text color, shadow, background panel, and which **items are shown**
- **Litematica your way**: tint, transparency, **block texture visibility**
- Settings are **saved per player**

### Marked Plots

- Fence off a rectangular plot with the **marking stick**; boxes placed inside bind to it automatically, and the four corners are snapshotted and fixed
- Clear plot boundaries drawn as a **light curtain**

### Multiplayer

- **Build-area conflict detection**: the same area is never built twice — first come, first served
- **Safety checks**: actions are rate-limited per player to keep high-frequency requests from overwhelming the server

---

## Credits

- **Every player who ever uploaded a building to the Sim-U-Kraft community**
- [Satscape](https://www.youtube.com/satscapeminecraft) and Sim-U-Kraft
- The NeoForge community and the Litematica project

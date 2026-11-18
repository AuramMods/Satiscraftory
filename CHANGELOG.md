# Changelog

## Unreleased

- Add a green-tinted Smart Splitter with independent output modes, nine ghost item filters per side, optional JEI dragging, and overflow routing that respects both pushes and pulls.
- Add the Smart Splitter crafting recipe and guidebook documentation.

## 1.1.1 — 2026-11-17

- Prevent crafting machines from losing an output item when a receiving belt pulls from a multi-item recipe batch. Six planks now deliver all three doors; a full stack delivers 30 doors and leaves four planks.

## 1.1.0 — 2026-11-17

- Add the instant RF-powered Smelter with unchanged passthrough for items without a smelting recipe.
- Add the Constructor, Assembler, and Manufacturer for recipes using one, up to two, and up to four ingredient types respectively.
- Add ghost recipe templates, selectable recipe alternatives, optional JEI dragging, crafting recipes, and guidebook entries for the new machines.
- Limit Assembler and Manufacturer intake to one buffer per item type and two crafts' worth of each ingredient. Preserve existing surplus for crafting or removal.
- Support repeated unstackable crafting inputs, retain crafting remainders, and preserve paid results under output backpressure and save/reload.
- Show compact recipe icons with native inventory stack counts, hiding counts of one.
- Synchronize full-width machine energy values, including the Manufacturer's 40,000 RF buffer.
- Restore pick-block targeting across entire conveyor routes for all six tiers in survival and creative, including remapped pick keys and belts backed by air.
- Recolor the field-guide interface orange and gold.

Minecraft 1.20.1, Forge 47.4.10, Java 17. JEI and Patchouli remain optional.

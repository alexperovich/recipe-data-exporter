# Replacing the KubeJS Export with Live APIs — Proposed Design Report

## 1. Goal

Today the exporter is split in two:

- **`RecipeTypeHandler` family** — already pulls live data from the **JEI API**
  (`IRecipeManager`, `IRecipeCategory`, catalyst lookup). It produces
  [examples/output/recipeTypes.json](examples/output/recipeTypes.json).
- **`RecipeDataHandler` family** — reads the **KubeJS recipe export** JSON files
  from `local/kubejs/export/recipes` and re-parses them into `RecipeExport`
  objects (see [MinecraftRecipeDataHandler.java](src/main/java/dev/alperovi/recipedataexporter/handler/MinecraftRecipeDataHandler.java),
  [CreateRecipeDataHandler.java](src/main/java/dev/alperovi/recipedataexporter/handler/CreateRecipeDataHandler.java),
  [GregTechRecipeDataHandler.java](src/main/java/dev/alperovi/recipedataexporter/handler/GregTechRecipeDataHandler.java)).

We want to **delete the KubeJS dependency** and source recipe *data* from the
same live game/JEI APIs already used for recipe *types*. This report maps every
field of `RecipeExport` to a concrete API and proposes a handler architecture.

> Versions in scope (from [gradle.properties](gradle.properties)): MC `1.20.1`,
> Forge `47.3.0`, JEI `15.20.0.130`, GTCEu `7.5.3`, plus Create + addons.
> Where an API differs from the newer branches I read on GitHub, the version
> caveat is called out explicitly in [§9](#9-version-caveats-must-verify).

---

## 2. What data we must produce

### `RecipeExport` ([model/RecipeExport.java](src/main/java/dev/alperovi/recipedataexporter/model/RecipeExport.java))

| Field | Type | Meaning | Hard to source? |
|-------|------|---------|-----------------|
| `id` | `String` | recipe id, e.g. `minecraft:acacia_button` | easy |
| `type` | `String` | exported recipe-type id (after grouping/remapping) | easy |
| `fullTypeName` | `String` | original/source recipe type id | easy |
| `data` | `Map<String,Object>` | arbitrary extras (GT `ebf_temp`, `vacuum_level`) | GT only |
| `duration` | `long` | processing ticks | medium |
| `voltage` | `long` | EU/t (GT), negative for generators | GT only |
| `itemInputs` | `Map<slot, ItemIngredientExport>` | `i{tem|tag, count, probability, nbt}` | **tags/prob tricky** |
| `fluidInputs` | `Map<slot, FluidIngredientExport>` | `{fluid|tag, amount}` | **tags tricky** |
| `itemOutputs` | same | | |
| `fluidOutputs` | same | | |

### `ItemIngredientExport` / `FluidIngredientExport`
`{item, tag, count, probability, nbt}` and `{fluid, tag, amount}` — see
[model/ItemIngredientExport.java](src/main/java/dev/alperovi/recipedataexporter/model/ItemIngredientExport.java)
and [model/FluidIngredientExport.java](src/main/java/dev/alperovi/recipedataexporter/model/FluidIngredientExport.java).

The two genuinely awkward concepts are **tags** (JEI flattens tags into a list of
concrete `ItemStack`s, losing the tag identity that the KubeJS JSON preserved)
and **probability** (chanced outputs). Both are addressed below.

---

## 3. The core idea

The `RecipeTypeExportService` already enumerates **every** JEI category:

```java
recipeManager.createRecipeCategoryLookup().get()
    .forEach(category -> ...);   // RecipeTypeExportService#exportRecipeTypes
```

For each `IRecipeCategory<T>` we can additionally pull **the recipe objects of
that category** and convert them to `RecipeExport`. JEI exposes this:

```java
// JEI 15.x — mezz.jei.api.recipe.IRecipeManager
<R> IRecipeLookup<R> createRecipeLookup(IRecipeType<R> recipeType);
// IRecipeLookup<R>: .includeHidden().get() -> Stream<R>
```

So the data exporter no longer needs files on disk — it walks the same category
list and, per category, does:

```java
IRecipeType<T> type = category.getRecipeType();
recipeManager.createRecipeLookup(type)
    .includeHidden()
    .get()                       // Stream<T> of the actual recipe objects
    .forEach(recipe -> convert(category, recipe));
```

`T` is the concrete recipe class (`RecipeHolder<CraftingRecipe>`, `GTRecipe`,
`BasinRecipe`, …). From here there are **two extraction strategies**, used
together:

- **(A) Generic, category-driven** — works for *any* category, including mods we
  have no code for. Drives the category's own `setRecipe(...)` layout logic and
  records the slots. Great for item/fluid in/out **structure** and grid
  positions; weaker on tags/probability/duration.
- **(B) Type-specific, recipe-object-driven** — cast `recipe` to its known class
  (vanilla `Recipe`, `GTRecipe`, Create `ProcessingRecipe`) and read fields
  directly. Required for `duration`, `voltage`, `data`, chanced outputs, and
  reliable tag preservation.

The proposed handlers use (B) for the three families we own (minecraft / create /
gtceu) and (A) as the generic fallback that replaces today's "placeholder"
behaviour in [RecipeDataExportService](src/main/java/dev/alperovi/recipedataexporter/export/RecipeDataExportService.java).

---

## 4. Strategy A — generic layout recorder (the JEI-native trick)

JEI builds every recipe's ingredient list by calling the category's
`setRecipe(IRecipeLayoutBuilder, T, IFocusGroup)` and recording what the category
adds. We can do the exact same thing by passing a **recording implementation of
`IRecipeLayoutBuilder`** (this is literally how JEI's internal
`IngredientSupplierBuilder` works).

```java
// pseudo-implementation
class RecordingLayoutBuilder implements IRecipeLayoutBuilder {
    // capture (role, x, y) + ingredient list per slot
    IRecipeSlotBuilder addSlot(RecipeIngredientRole role, int x, int y) { ... }
    IIngredientAcceptor<?> addInvisibleIngredients(RecipeIngredientRole role) { ... }
    void moveRecipeTransferButton(int x, int y) {}
    void setShapeless() {}            void setShapeless(int x,int y) {}
    void createFocusLink(IIngredientAcceptor<?>... slots) {}
}

IFocusGroup empty = jeiHelpers.getFocusFactory().getEmptyFocusGroup();
category.setRecipe(recorder, recipe, empty);
```

Each recorded `IRecipeSlotBuilder` (an `IIngredientAcceptor`) receives ingredients
through methods we intercept:

- `addItemStack(ItemStack)` / `addItemStacks(List<ItemStack>)`
- `addFluidStack(Fluid, long)` / `addFluidStacks(...)` (Forge `FluidStack`)
- `addIngredients(Ingredient)` ← **vanilla `Ingredient`, tag still intact**
- `addIngredients(IIngredientType, List)` / `addTypedIngredients(...)`
- `addIngredientsUnsafe(List<?>)`

What we get:

- **Role** (`INPUT`, `OUTPUT`, `CATALYST`) → which map to write into.
- **Ingredient type** (`VanillaTypes.ITEM_STACK` vs Forge `FLUID_STACK`) → item
  vs fluid map.
- **Slot (x, y)** → can be clustered into a grid, i.e. a *runtime-derived*
  alternative to the hand-maintained dimension tables in
  [CreateRecipeTypeHandler](src/main/java/dev/alperovi/recipedataexporter/handler/CreateRecipeTypeHandler.java)
  and [MinecraftRecipeTypeHandler](src/main/java/dev/alperovi/recipedataexporter/handler/MinecraftRecipeTypeHandler.java).
- **Item count / nbt** from `ItemStack`; **fluid amount** from `FluidStack`.

Limitations of Strategy A:

- When a category adds an *expanded list* of `ItemStack`s (tag already flattened),
  the **tag name is lost**. We can only recover `tag` when the category hands us a
  raw vanilla `Ingredient`/`FluidIngredient` (then read it via the tag-detection
  in [§7](#7-tags-and-probability-the-tricky-bits)).
- **No `probability`, `duration`, `voltage`, `data`** — these never reach the
  layout builder. Strategy B is required for them.

> Net: Strategy A is the right replacement for the generic *placeholder* path and
> can also **auto-derive the IO grid dimensions** that `RecipeTypeHandler`
> subclasses hard-code today. But the three owned families need Strategy B.

---

## 5. Strategy B — per-family recipe-object extraction

### 5.1 Vanilla Minecraft (`minecraft:*` + crafting-format addons)

JEI wraps vanilla recipes as `RecipeHolder<T>` (so `createRecipeLookup` yields
`RecipeHolder<CraftingRecipe>` etc.). Unwrap with `holder.id()` and
`holder.value()`.

| Need | API |
|------|-----|
| recipe `id` | `RecipeHolder#id()` → `ResourceLocation` (or `IRecipeCategory#getRegistryName(recipe)`) |
| item inputs | `Recipe#getIngredients()` → `NonNullList<Ingredient>` |
| shaped grid | `ShapedRecipe#getWidth()` / `getHeight()` (cast); shapeless = flat list |
| item output | `Recipe#getResultItem(RegistryAccess)` → `ItemStack` (count, nbt) |
| cooking input | `AbstractCookingRecipe#getIngredients().get(0)` |
| duration | `AbstractCookingRecipe#getCookingTime()` (smelt/blast/smoke/campfire) |
| stonecutting | `StonecutterRecipe` (1 in → 1 out) |
| smithing | `SmithingRecipe` (template/base/addition ingredients) |

- `RegistryAccess` for `getResultItem` comes from the server:
  `server.registryAccess()` (the command already runs server-side, see
  [ExportDataCommand](src/main/java/dev/alperovi/recipedataexporter/command/ExportDataCommand.java)).
- Slot indices for shaped recipes: reproduce the current `row*width + col`
  numbering from `ShapedRecipe` width/height instead of parsing the KubeJS
  `pattern`/`key`.
- Tag vs item per `Ingredient`: see [§7](#7-tags-and-probability-the-tricky-bits).

This fully replaces `MinecraftRecipeDataHandler`'s JSON parsing, including the
non-vanilla "uses crafting format" types (`enderchests:crafting`,
`kubejs:shaped`, …) because they are real `CraftingRecipe`s at runtime and arrive
through the same crafting categories.

### 5.2 GregTech (`gtceu:*`)

`createRecipeLookup` for a GT category yields **`GTRecipe`** objects directly
(`GTRecipe implements net.minecraft.world.item.crafting.Recipe<Container>`).
`GTRecipe` exposes everything the KubeJS JSON had, as public fields/methods:

```java
// com.gregtechceu.gtceu.api.recipe.GTRecipe
public ResourceLocation id;
public int duration;                       // -> RecipeExport.duration
public CompoundTag data;                   // -> RecipeExport.data (ebf_temp, vacuum_level, ...)
public final Map<RecipeCapability<?>, List<Content>> inputs, outputs, tickInputs, tickOutputs;
List<Content> getInputContents(RecipeCapability<?> cap);
List<Content> getOutputContents(RecipeCapability<?> cap);
List<Content> getTickInputContents(RecipeCapability<?> cap);
```

| Need | API |
|------|-----|
| item inputs/outputs | `recipe.getInputContents(ItemRecipeCapability.CAP)` then `ItemRecipeCapability.CAP.of(content.getContent())` → `Ingredient` |
| fluid inputs/outputs | `recipe.getInputContents(FluidRecipeCapability.CAP)` then `FluidRecipeCapability.CAP.of(...)` → Forge `FluidIngredient` (has amount) |
| count | `Content.getContent()` is a *sized* `Ingredient`; GT keeps amount on the ingredient (`IntProviderIngredient`/`SizedIngredient`) |
| probability | `Content.chance` / `Content.maxChance` (10000 == 100%, matching today's export) |
| circuit | detect GT `IntCircuitIngredient` → emit `gtceu:programmed_circuit` + configuration (same as current handler) |
| duration | `recipe.duration` |
| voltage | `RecipeHelper.getRealEUtWithIO(recipe)` → `EnergyStack.WithIO`: `.voltage()` and `.isInput()/.io()`; negate for generators (today's `parseVoltage` logic) |
| data | iterate `recipe.data` (`CompoundTag`) entries |
| EU as content | alternatively `recipe.getTickInputContents(EURecipeCapability.CAP)` |

`RecipeCapability` and the cap singletons (`ItemRecipeCapability.CAP`,
`FluidRecipeCapability.CAP`, `EURecipeCapability.CAP`) are already on the
classpath — `GregTechRecipeTypeHandler` uses them today. This makes GT the
*cleanest* migration: no JSON, all data is on the object.

### 5.3 Create + addons

Create processing recipes extend
`com.simibubi.create.content.processing.recipe.ProcessingRecipe` (basin/mixing,
crushing, milling, pressing, sawing, fan_*, etc.). The category yields the recipe
object directly.

| Need | API (1.20.1 Create) |
|------|---------------------|
| item inputs | `ProcessingRecipe#getIngredients()` → `NonNullList<Ingredient>` |
| fluid inputs | `ProcessingRecipe#getFluidIngredients()` → `List<FluidIngredient>` (Forge; has amount) |
| item outputs + chance | `ProcessingRecipe#getRollableResults()` → `List<ProcessingOutput>`; `ProcessingOutput#getStack()` (item/count/nbt) + `getChance()` (float 0–1 → `*10000` to match export) |
| fluid outputs | `ProcessingRecipe#getFluidResults()` → `List<FluidStack>` |
| duration | `ProcessingRecipe#getProcessingDuration()` |
| type remap | keep the `compacting→packing`, `cutting→sawing`, `haunting→fan_haunting`, `splashing→fan_washing` map from `CreateRecipeDataHandler` |

`createdieselgenerators:basin_fermenting` and `create_new_age:energising` are
also `ProcessingRecipe`/`BasinRecipe` subclasses, so the same accessors apply.
The fan recipes (`create:fan_blasting`/`fan_smoking`) actually wrap vanilla
cooking recipes — handle them via the vanilla cooking path.

> Note: `ProcessingOutput.getChance()` is a **float in [0,1]** here, while the GT
> `Content.chance` is an **int out of 10000**. Today's `CreateRecipeDataHandler`
> already does `Math.round(chance * 10000)` — keep that normalization.

---

## 6. Field-by-field source matrix

| `RecipeExport` field | Vanilla | GregTech | Create | Generic (A) |
|----------------------|---------|----------|--------|-------------|
| `id` | `RecipeHolder.id()` | `GTRecipe.id` | `IRecipeCategory.getRegistryName(recipe)` | `getRegistryName(recipe)` |
| `type` (grouped) | category UID + group rules | `recipe.recipeType` UID | source UID + remap table | category UID |
| `fullTypeName` (source) | `recipe.getType()` registry id | `gtceu:<type>` | `recipe.getType()` id | category UID |
| `duration` | `getCookingTime()` / 0 | `recipe.duration` | `getProcessingDuration()` | ✗ (0) |
| `voltage` | 0 | `RecipeHelper.getRealEUtWithIO` | 0 | ✗ (0) |
| `data` | `{}` | `recipe.data` (CompoundTag) | `{}` | `{}` |
| item in/out | `getIngredients()` / `getResultItem()` | cap `getInputContents/OutputContents` | `getIngredients()` / `getRollableResults()` | recorded slots |
| fluid in/out | n/a | fluid cap contents | `getFluidIngredients()` / `getFluidResults()` | recorded fluid slots |
| `count` | `ItemStack.getCount()` | sized `Ingredient` amount | `ItemStack.getCount()` | `ItemStack.getCount()` |
| `probability` | n/a | `Content.chance` | `ProcessingOutput.getChance()*10000` | ✗ |
| `nbt` | `ItemStack` tag | ingredient/stack tag | `ItemStack` tag | `ItemStack` tag |
| `tag` | from `Ingredient` (see §7) | from cap `Ingredient` | from `Ingredient` | only if raw `Ingredient` |

---

## 7. Tags and probability (the tricky bits)

### Tags
JEI display ingredients flatten a tag into many `ItemStack`s, so to preserve the
`tag` field we must inspect the **recipe object's `Ingredient`**, not the JEI
slot. Two viable options on 1.20.1:

1. **`Ingredient.toJson()`** (vanilla, public in 1.20.1) returns
   `{"tag":"forge:ingots/iron"}` or `{"item":"minecraft:iron_ingot"}`. Parse the
   single key — robust and mirrors exactly what the KubeJS export encoded.
   Forge `FluidIngredient` similarly has `toJson()`.
2. **`Ingredient.getValues()`** → `Ingredient.Value[]`; a `TagValue` vs
   `ItemValue` distinguishes tag from item. More work and the inner classes are
   not all public — prefer option 1.

For **GregTech**, `ItemRecipeCapability.CAP.of(content.getContent())` returns a
vanilla `Ingredient` (often a GT `SizedIngredient`/`IntProviderIngredient`
wrapping one); unwrap to the base `Ingredient` then apply the same
`toJson()`-based tag detection. Fluids use Forge `FluidIngredient.toJson()`.

> Recommendation: add a small shared helper
> `IngredientExports.fromIngredient(Ingredient, count)` and
> `fromFluidIngredient(FluidIngredient)` that encapsulate the tag-vs-item
> decision, so all four handlers share one code path. This replaces the
> per-handler `parseItemIngredient`/`parseItem`/`parseFluid` JSON parsers.

### Probability
- GregTech: already an int/10000 on `Content.chance` — use directly.
- Create: float in [0,1] on `ProcessingOutput.getChance()` — multiply by 10000.
- Vanilla: no chanced outputs → leave `null` (today's behaviour).

---

## 8. Proposed handler architecture

Keep the existing **registry + predicate** dispatch (it is clean), but change the
handler input from `JsonObject` to the **typed recipe object + its category**:

```java
public abstract class RecipeDataHandler {
    // old: convert(String recipeId, JsonObject recipeJson)
    // new:
    public abstract RecipeExport convert(IRecipeCategory<?> category, Object recipe,
                                         RegistryAccess registryAccess);
    static boolean canHandle(IRecipeCategory<?> category); // mirror RecipeTypeHandler
}
```

- `IgnoreRecipeDataHandler` → predicate on the category UID (reuse
  `IgnoreRecipeTypeHandler.isIgnoredType`), no conversion.
- `MinecraftRecipeDataHandler` → handles vanilla/crafting categories via §5.1.
- `CreateRecipeDataHandler` → handles Create categories via §5.3.
- `GregTechRecipeDataHandler` → handles `gtceu:*` via §5.2.
- **New** `GenericRecipeDataHandler` (Strategy A) → replaces the placeholder for
  unowned categories, emitting whatever item/fluid IO it can record.

Driver (`RecipeDataExportService`) changes:

```java
for (IRecipeCategory<?> category : recipeManager.createRecipeCategoryLookup().get()) {
    if (ignored(category)) continue;
    var handler = pick(category);
    recipeManager.createRecipeLookup(category.getRecipeType())
        .includeHidden().get()
        .forEach(recipe -> grouped
            .computeIfAbsent(export.type(), k -> new ArrayList<>())
            .add(handler.convert(category, recipe, registryAccess)));
}
```

This **deletes**: the `Files.walk` input scan, `deriveRecipeId`,
`hasRecipeExportData`, the `local/kubejs/export/recipes` precondition in
[ExportDataCommand](src/main/java/dev/alperovi/recipedataexporter/command/ExportDataCommand.java),
and all `JsonObject` parsing in the data handlers. Output grouping/serialization
(`serializeToJson`, `resolveOutputPath`) is unchanged.

Bonus: because both services now iterate the **same** category list with the same
JEI lookup, the type-dimension tables in the `RecipeTypeHandler`s can optionally
be **derived** from Strategy A's recorded slot positions, removing the
hard-coded `IO_COUNTS` map in `CreateRecipeTypeHandler` and the per-uid switches
in `MinecraftRecipeTypeHandler` (GregTech already derives them from
`GTRecipeType.getMaxInputs/Outputs`).

---

## 9. Version caveats (must verify)

The GitHub sources I read were the latest branches; confirm these against the
**bundled** `jei 15.20.0.130` / `gtceu 7.5.3` / Create 1.20.1 jars:

1. **`IRecipeManager.getRecipeIngredients(category, recipe)`** is `@since 19.9.0`
   → **NOT available in JEI 15.x**. That is exactly why Strategy A must implement
   its own recording `IRecipeLayoutBuilder` (JEI 19+ could use the one-liner
   instead). The recording approach itself works on 15.x.
2. **`IRecipeCategory#getRegistryName(T)`** is the 1.20.1 method for the recipe
   id (it was renamed to `getIdentifier` and deprecated only in JEI 26/27).
3. **`IRecipeLayoutBuilder` slot methods** on 15.x: implement `addSlot(role,x,y)`,
   `addInvisibleIngredients(role)`, `moveRecipeTransferButton`, `setShapeless()`,
   `setShapeless(x,y)`, `createFocusLink`. The no-arg `addInputSlot/addOutputSlot`
   convenience overloads are 19.19+ and need not be implemented.
4. **Forge fluid type**: JEI's Forge fluid ingredient type is
   `mezz.jei.api.forge.ForgeTypes.FLUID_STACK` (`IIngredientType<FluidStack>`,
   `net.minecraftforge.fluids.FluidStack`). Confirm the exact constant name in
   the bundled forge-api jar.
5. **`Ingredient.toJson()`** signature on 1.20.1 (no-arg `toJson()` returning
   `JsonElement`; the `toJson(boolean)` overload is newer). Verify before relying
   on it for tag detection.
6. **Create `ProcessingRecipe`** on 1.20.1 returns Forge `FluidIngredient` /
   `FluidStack` (not 1.21's `SizedFluidIngredient`). `ProcessingOutput.getChance()`
   returns a `float`; `getStack()` returns the output `ItemStack`.
7. **GT `Content`** field visibility (`chance`, `maxChance`, `tierChanceBoost`,
   `getContent()`) and `RecipeHelper.getRealEUtWithIO` exist in 7.5.3 (used by GT's
   own JEI/Jade integration), but confirm method names against the deobf jar.

---

## 10. Risks & open questions

- **Client vs server timing.** JEI runtime is client-side; the recipe *objects*
  it hands back are the same instances loaded from the integrated/dedicated
  server's `RecipeManager`. `getResultItem` needs a `RegistryAccess` — use the
  server's. On a dedicated server JEI is absent, so the export must continue to
  run from the client (as it does now via `DataExportPlugin`).
- **Tag fidelity.** If a third-party category pre-expands tags before handing
  them to the layout builder, Strategy A cannot recover the tag. Owned families
  (B) are unaffected because they read the recipe's own `Ingredient`.
- **Sized ingredients.** GT/Create wrap counts inside custom `Ingredient`s; make
  sure the shared helper unwraps `SizedIngredient`/`IntProviderIngredient` to read
  `count` rather than defaulting to 1.
- **Multi-category recipes.** A GT recipe can appear under an "extra" category
  (already handled for *types* via `GTRecipeCategories.get(path)` in
  [GregTechRecipeTypeHandler](src/main/java/dev/alperovi/recipedataexporter/handler/GregTechRecipeTypeHandler.java));
  ensure data export keys on the same grouped `type` to avoid duplicates.
- **Parity check.** Before deleting KubeJS, run both pipelines and diff the
  `recipes/**` output to catch tag/probability/slot-index regressions.

---

## 11. Summary of recommended APIs

- **Enumerate**: `IRecipeManager.createRecipeCategoryLookup().get()` (already used).
- **Fetch recipes per type**: `IRecipeManager.createRecipeLookup(type).includeHidden().get()`.
- **Recipe id**: `RecipeHolder.id()` / `IRecipeCategory.getRegistryName(recipe)`.
- **Generic IO + grid**: recording `IRecipeLayoutBuilder` via `category.setRecipe(...)`
  with `jeiHelpers.getFocusFactory().getEmptyFocusGroup()`.
- **Vanilla**: `Recipe#getIngredients()`, `ShapedRecipe#getWidth/Height`,
  `Recipe#getResultItem(RegistryAccess)`, `AbstractCookingRecipe#getCookingTime`.
- **GregTech**: `GTRecipe` fields (`inputs/outputs/tickInputs/tickOutputs`,
  `duration`, `data`, `id`) + `RecipeCapability.of` + `RecipeHelper.getRealEUtWithIO`.
- **Create**: `ProcessingRecipe#getIngredients/getFluidIngredients/getRollableResults/
  getFluidResults/getProcessingDuration`, `ProcessingOutput#getStack/getChance`.
- **Tags**: `Ingredient.toJson()` / `FluidIngredient.toJson()` shared helper.

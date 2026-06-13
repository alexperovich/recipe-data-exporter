package dev.alperovi.recipedataexporter.export;

import dev.alperovi.recipedataexporter.model.FluidExport;
import dev.alperovi.recipedataexporter.model.ItemExport;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Deduplicating registry of the unique item and fluid data referenced across all
 * recipes. Each distinct item (id/tag plus nbt) or fluid (id/tag) is stored once
 * and addressed by a stable string key. Recipes reference these entries by key
 * via {@link dev.alperovi.recipedataexporter.model.ItemStackExport} and
 * {@link dev.alperovi.recipedataexporter.model.FluidStackExport}.
 *
 * <p>Keys are derived from the entry's identifying data so that equal items or
 * fluids always intern to the same key:
 * <ul>
 *   <li>the prefix is the item/fluid id as-is, or {@code #<tag>} for tag-based
 *       entries;</li>
 *   <li>for items, an {@code #<hash>} suffix is appended when nbt is present,
 *       where {@code hash} is the first 8 hex characters of the SHA-256 of the
 *       nbt string.</li>
 * </ul>
 */
public class ExportTables {

    private final Map<String, ItemExport> items = new LinkedHashMap<>();
    private final Map<String, FluidExport> fluids = new LinkedHashMap<>();

    /**
     * A representative concrete stack for each interned key, captured at intern
     * time. Used by the post-processing metadata pass to compute display name,
     * mod name, tooltip and to render the icon. Tag-based entries store the first
     * matching member as their representative.
     */
    private final Map<String, ItemStack> itemRepresentatives = new LinkedHashMap<>();
    private final Map<String, FluidStack> fluidRepresentatives = new LinkedHashMap<>();

    /**
     * Interns an item or item tag, returning the lookup key that resolves it in
     * the item table. Exactly one of {@code id} or {@code tag} is expected to be
     * non-null; {@code nbt} may be null. The {@code representative} stack (may be
     * null) is recorded for later metadata/icon extraction.
     */
    public String internItem(String id, String tag, String nbt, ItemStack representative) {
        String prefix = tag != null ? "#" + tag : id;
        String key = nbt != null ? prefix + "#" + sha256Short(nbt) : prefix;
        items.putIfAbsent(key, new ItemExport(id, tag, nbt, null, null, null, null, null));
        if (representative != null && !representative.isEmpty()) {
            itemRepresentatives.putIfAbsent(key, representative);
        }
        return key;
    }

    /**
     * Interns a fluid or fluid tag, returning the lookup key that resolves it in
     * the fluid table. Exactly one of {@code id} or {@code tag} is expected to be
     * non-null. The {@code representative} stack (may be null) is recorded for
     * later metadata/icon extraction.
     */
    public String internFluid(String id, String tag, FluidStack representative) {
        String key = tag != null ? "#" + tag : id;
        fluids.putIfAbsent(key, new FluidExport(id, tag, null, null, null, null));
        if (representative != null && !representative.isEmpty()) {
            fluidRepresentatives.putIfAbsent(key, representative);
        }
        return key;
    }

    /**
     * Returns an unmodifiable view of the interned item table keyed by lookup key.
     */
    public Map<String, ItemExport> items() {
        return Collections.unmodifiableMap(items);
    }

    /**
     * Returns an unmodifiable view of the interned fluid table keyed by lookup key.
     */
    public Map<String, FluidExport> fluids() {
        return Collections.unmodifiableMap(fluids);
    }

    /**
     * Returns the representative {@link ItemStack} captured for the given key, or
     * null when none was recorded.
     */
    public ItemStack itemRepresentative(String key) {
        return itemRepresentatives.get(key);
    }

    /**
     * Returns the representative {@link FluidStack} captured for the given key, or
     * null when none was recorded.
     */
    public FluidStack fluidRepresentative(String key) {
        return fluidRepresentatives.get(key);
    }

    /**
     * Replaces the item entry for an existing key with an enriched record. No-op
     * when the key is not present in the table.
     */
    public void replaceItem(String key, ItemExport enriched) {
        if (items.containsKey(key)) {
            items.put(key, enriched);
        }
    }

    /**
     * Replaces the fluid entry for an existing key with an enriched record. No-op
     * when the key is not present in the table.
     */
    public void replaceFluid(String key, FluidExport enriched) {
        if (fluids.containsKey(key)) {
            fluids.put(key, enriched);
        }
    }

    /**
     * Returns the set of distinct item tag ids referenced by the interned item
     * entries, in first-seen order.
     */
    public Set<String> itemTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (ItemExport item : items.values()) {
            if (item.tag() != null) {
                tags.add(item.tag());
            }
        }
        return tags;
    }

    /**
     * Returns the set of distinct fluid tag ids referenced by the interned fluid
     * entries, in first-seen order.
     */
    public Set<String> fluidTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (FluidExport fluid : fluids.values()) {
            if (fluid.tag() != null) {
                tags.add(fluid.tag());
            }
        }
        return tags;
    }

    /**
     * Returns the first 8 hex characters of the SHA-256 digest of the given
     * string, used as a compact, stable suffix distinguishing nbt variants.
     */
    private static String sha256Short(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                builder.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                builder.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on every JVM.
            return Integer.toHexString(value.hashCode());
        }
    }
}

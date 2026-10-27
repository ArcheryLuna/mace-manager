package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class MaceItems {
    private final NamespacedKey idKey;
    private final NamespacedKey untrackedKey;

    public MaceItems(JavaPlugin plugin) {
        this.idKey = new NamespacedKey(plugin, "id");
        this.untrackedKey = new NamespacedKey(plugin, "untracked");
    }

    public boolean isMace(ItemStack stack) {
        return stack != null && stack.getType() == Material.MACE;
    }

    public boolean isUntracked(ItemStack stack) {
        return classify(stack).untracked();
    }

    public UUID trackedId(ItemStack stack) {
        return classify(stack).id();
    }

    // A short-lived value only; never retain item metadata across events.
    public Classification classify(ItemStack stack) {
        if (!isMace(stack)) {
            return Classification.OTHER;
        }
        if (!stack.hasItemMeta()) {
            return Classification.UNTAGGED;
        }
        var data = stack.getItemMeta().getPersistentDataContainer();
        Byte flag = data.get(untrackedKey, PersistentDataType.BYTE);
        if (flag != null && flag == (byte) 1) {
            return Classification.UNTRACKED;
        }
        String raw = data.get(idKey, PersistentDataType.STRING);
        if (raw != null && !raw.isBlank()) {
            try {
                return new Classification(true, false, UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // Malformed IDs are illegal, just like untagged maces.
            }
        }
        return Classification.UNTAGGED;
    }

    public record Classification(boolean mace, boolean untracked, UUID id) {
        private static final Classification OTHER = new Classification(false, false, null);
        private static final Classification UNTAGGED = new Classification(true, false, null);
        private static final Classification UNTRACKED = new Classification(true, true, null);
    }

    public TaggedMace createTracked() {
        UUID id = UUID.randomUUID();
        ItemStack stack = new ItemStack(Material.MACE);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        stack.setItemMeta(meta);
        return new TaggedMace(id, stack);
    }

    public ItemStack createUntracked() {
        ItemStack stack = new ItemStack(Material.MACE);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(untrackedKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean containsMace(Iterable<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (isMace(stack)) {
                return true;
            }
        }
        return false;
    }

    public record TaggedMace(UUID id, ItemStack stack) {
    }
}

package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class PlayerMaceCleanup {
    private static final EquipmentSlot[] STAND_SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private final MaceManagerPlugin plugin;
    private int illegalRemoved;

    private record Slot(Object inventory, Object index) {
    }

    private record BlockInventory(String world, int x, int y, int z) {
    }

    private record Site(Object key, MaceLocation location, Player possessor,
                        boolean allowed, Supplier<ItemStack> live) {
    }

    private record Inspection(ItemStack item, boolean changed) {
    }

    public PlayerMaceCleanup(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    void clean(Player player, MaceObservations seen) {
        illegalRemoved = 0;
        List<ItemStack> ejected = new ArrayList<>();
        Inventory ender = player.getEnderChest();
        if (ender != null) {
            inventory(ender, MaceLocation.playerStorage("ENDER_CHEST", player.getUniqueId()),
                    null, !StorageRules.blocksMace(ender, plugin.settings()), seen, ejected, player::isOnline);
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top != null && top != ender && top != player.getInventory()) {
            // Crafting results are previews, not another physical mace. Never count/eject them.
            boolean workstation = switch (top.getType().name()) {
                case "ANVIL", "ENCHANTING", "GRINDSTONE", "CRAFTING", "WORKBENCH" -> true;
                default -> false;
            };
            MaceLocation location = workstation ? MaceLocation.carried() : location(top, player);
            inventory(top, location, workstation ? player : null,
                    !StorageRules.blocksMace(top, plugin.settings()), seen, ejected, player::isOnline);
        }
        give(player, ejected);
        ejected.clear();
        inventory(player.getInventory(), MaceLocation.carried(), player, true, seen, ejected, player::isOnline);
        ItemStack cursor = player.getItemOnCursor();
        Site cursorSite = new Site(new Slot(player.getUniqueId(), "cursor"), MaceLocation.carried(), player,
                true, () -> player.isOnline() ? player.getItemOnCursor() : null);
        Inspection result = inspect(cursor, cursorSite, seen, ejected);
        if (result.changed()) {
            player.setItemOnCursor(result.item());
        }
        if (!ejected.isEmpty()) {
            give(player, ejected);
            // Extracted maces now have real player slots; revisiting the same slots is safe.
            inventory(player.getInventory(), MaceLocation.carried(), player, true, seen,
                    new ArrayList<>(), player::isOnline);
        }
        if (illegalRemoved > 0) {
            Notices.send(player, plugin.settings().format("illegal-removed"));
        }
    }

    void queueChunk(Chunk chunk, ScanQueue.Job job, MaceObservations seen) {
        if (!chunk.isLoaded()) {
            return;
        }
        for (BlockState state : plugin.chunkAccess().tiles(chunk)) {
            // Shelves implement InventoryHolder but are not Containers.
            if (state instanceof InventoryHolder) {
                var block = state.getBlock();
                job.add(() -> {
                    if (chunk.isLoaded() && plugin.chunkAccess().state(block) instanceof InventoryHolder holder) {
                        List<ItemStack> ejected = new ArrayList<>();
                        Inventory inventory = StorageRules.inventory(holder);
                        inventory(inventory, blockLocation(inventory.getType().name(), block.getLocation()),
                                null, !StorageRules.blocksMace(inventory, plugin.settings()), seen, ejected, chunk::isLoaded);
                        release(ejected, block.getLocation());
                    }
                });
            }
        }
        for (Entity entity : chunk.getEntities()) {
            job.add(() -> {
                Location location = entity.getLocation();
                if (entity.isValid() && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    entity(entity, seen);
                }
            });
        }
    }

    void scanChunkNow(Chunk chunk, MaceObservations seen) {
        if (!chunk.isLoaded()) {
            return;
        }
        for (BlockState state : plugin.chunkAccess().tiles(chunk)) {
            if (state instanceof InventoryHolder holder) {
                List<ItemStack> ejected = new ArrayList<>();
                Inventory inventory = StorageRules.inventory(holder);
                inventory(inventory, blockLocation(inventory.getType().name(), state.getLocation()), null,
                        !StorageRules.blocksMace(inventory, plugin.settings()), seen, ejected, chunk::isLoaded);
                release(ejected, state.getLocation());
            }
        }
        for (Entity entity : chunk.getEntities()) {
            if (entity.isValid()) {
                entity(entity, seen);
            }
        }
    }

    void entity(Entity entity, MaceObservations seen) {
        List<ItemStack> ejected = new ArrayList<>();
        if (entity instanceof ItemFrame frame) {
            Site site = entitySite(entity, "frame", !StorageRules.blocksMace(entity, plugin.settings()),
                    () -> entity.isValid() ? frame.getItem() : null);
            Inspection result = inspect(frame.getItem(), site, seen, ejected);
            if (result.changed()) {
                frame.setItem(result.item() == null ? new ItemStack(Material.AIR) : result.item());
            }
        } else if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : STAND_SLOTS) {
                Site site = entitySite(entity, slot, !StorageRules.blocksMace(entity, plugin.settings()),
                        () -> entity.isValid() ? stand.getItem(slot) : null);
                Inspection result = inspect(stand.getItem(slot), site, seen, ejected);
                if (result.changed()) {
                    stand.setItem(slot, result.item());
                }
            }
        } else if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
            Inventory inventory = StorageRules.inventory(holder);
            inventory(inventory, entityLocation(entity, MaceLocation.State.STORED), null,
                    !StorageRules.blocksMace(inventory, plugin.settings()), seen, ejected, entity::isValid);
        }
        release(ejected, entity.getLocation());
    }

    void ground(org.bukkit.entity.Item item, MaceObservations seen) {
        List<ItemStack> ejected = new ArrayList<>();
        Site site = new Site(new Slot(item.getUniqueId(), "item"), entityLocation(item, MaceLocation.State.STORED),
                null, true, () -> item.isValid() && !item.isDead() ? item.getItemStack() : null);
        Inspection result = inspect(item.getItemStack(), site, seen, ejected);
        if (result.changed()) {
            if (result.item() == null) {
                item.remove();
            } else {
                item.setItemStack(result.item());
            }
        }
        release(ejected, item.getLocation());
    }

    private Site entitySite(Entity entity, Object slot, boolean allowed, Supplier<ItemStack> live) {
        return new Site(new Slot(entity.getUniqueId(), slot), entityLocation(entity, MaceLocation.State.DISPLAYED),
                null, allowed, live);
    }

    private MaceLocation entityLocation(Entity entity, MaceLocation.State state) {
        Location location = entity.getLocation();
        return MaceLocation.entity(state, entity.getType().name(), location.getWorld().getName(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4, entity.getUniqueId());
    }

    private void inventory(Inventory inventory, MaceLocation location, Player possessor, boolean allowed,
                           MaceObservations seen, List<ItemStack> ejected, BooleanSupplier active) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            // Workstation output is derived from inputs; it must not count as a duplicate.
            String type = inventory.getType().name();
            if ((inventory instanceof org.bukkit.inventory.CraftingInventory && slot == 0)
                    || (type.equals("ANVIL") && slot == 2) || (type.equals("GRINDSTONE") && slot == 2)) {
                continue;
            }
            Inventory backing = inventory;
            int index = slot;
            MaceLocation physical = location;
            if (inventory instanceof org.bukkit.inventory.DoubleChestInventory combined) {
                int leftSize = combined.getLeftSide().getSize();
                backing = slot < leftSize ? combined.getLeftSide() : combined.getRightSide();
                index = slot < leftSize ? slot : slot - leftSize;
                Location side = backing.getLocation();
                if (side != null && side.getWorld() != null) {
                    if (!side.getWorld().isChunkLoaded(side.getBlockX() >> 4, side.getBlockZ() >> 4)) {
                        continue;
                    }
                    physical = blockLocation(inventory.getType().name(), side);
                }
            }
            Inventory source = backing;
            int sourceSlot = index;
            MaceLocation checkedLocation = physical;
            Object key = inventoryKey(source, physical, possessor);
            Site site = new Site(new Slot(key, index), physical, possessor, allowed,
                    () -> liveItem(source, sourceSlot, checkedLocation, active));
            Inspection result = inspect(source.getItem(index), site, seen, ejected);
            if (result.changed()) {
                source.setItem(index, result.item());
            }
        }
    }

    private boolean loaded(MaceLocation location) {
        if (location.world() == null) {
            return true;
        }
        var world = plugin.getServer().getWorld(location.world());
        return world != null && world.isChunkLoaded(location.chunkX(), location.chunkZ());
    }

    private Object inventoryKey(Inventory inventory, MaceLocation location, Player possessor) {
        if (inventory == (possessor == null ? null : possessor.getInventory())) {
            return possessor.getUniqueId();
        }
        if (location.entity() != null) {
            return location.entity();
        }
        if (location.inventoryOwner() != null && !location.storageType().startsWith("OPEN_")) {
            // Personal inventories remain distinct even when opened at the same block.
            return location;
        }
        if (location.state() != MaceLocation.State.CARRIED && location.storageType() != null
                && !location.storageType().startsWith("OPEN_")) {
            Location actual = inventory.getLocation();
            return actual != null && actual.getWorld() != null
                    ? new BlockInventory(actual.getWorld().getName(), actual.getBlockX(), actual.getBlockY(), actual.getBlockZ())
                    : new BlockInventory(location.world(), location.blockX(), location.blockY(), location.blockZ());
        }
        return inventory;
    }

    private ItemStack liveItem(Inventory inventory, int slot, MaceLocation location, BooleanSupplier active) {
        if (!active.getAsBoolean() || !loaded(location)) {
            return null;
        }
        if (location.blockX() != null) {
            // A captured inventory may outlive a broken/replaced block between scan units.
            var world = plugin.getServer().getWorld(location.world());
            var block = world.getBlockAt(location.blockX(), location.blockY(), location.blockZ());
            if (!(plugin.chunkAccess().state(block) instanceof InventoryHolder holder)) {
                return null;
            }
            Inventory current = StorageRules.inventory(holder);
            return slot < current.getSize() ? current.getItem(slot) : null;
        }
        if (inventory.getHolder() instanceof Entity entity && !(entity instanceof Player) && !entity.isValid()) {
            return null;
        }
        return inventory.getItem(slot);
    }

    private MaceLocation location(Inventory inventory, Player viewer) {
        if (inventory.getType().name().equals("ENDER_CHEST")) {
            return MaceLocation.playerStorage("ENDER_CHEST", viewer.getUniqueId());
        }
        if (inventory.getHolder() instanceof Entity entity) {
            return entityLocation(entity, MaceLocation.State.STORED);
        }
        Location location = inventory.getLocation();
        if (location != null && location.getWorld() != null) {
            return blockLocation(inventory.getType().name(), location);
        }
        return MaceLocation.playerStorage("OPEN_" + inventory.getType().name(), viewer.getUniqueId());
    }

    private MaceLocation blockLocation(String type, Location location) {
        return MaceLocation.block(MaceLocation.State.STORED, type, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private Inspection inspect(ItemStack stack, Site site, MaceObservations seen, List<ItemStack> ejected) {
        if (stack == null) {
            return new Inspection(null, false);
        }
        MaceItems.Classification classification = plugin.maceItems().classify(stack);
        if (classification.mace()) {
            var id = classification.id();
            boolean strict = plugin.settings().hasMaceLimit() && !classification.untracked();
            if (plugin.registry().isRevoked(id) || (strict && (id == null || !plugin.registry().contains(id)))) {
                illegalRemoved++;
                return new Inspection(null, true);
            }
            if (!site.allowed()) {
                ejected.add(stack.clone());
                return new Inspection(null, true);
            }
            if (id != null && plugin.registry().contains(id)) {
                boolean unique = seen.claim(id, site.key(), () -> id.equals(plugin.maceItems().trackedId(site.live().get())));
                if (strict && !unique) {
                    illegalRemoved++;
                    return new Inspection(null, true);
                }
                if (site.possessor() != null) {
                    plugin.registry().hold(id, site.possessor());
                } else {
                    plugin.registry().locate(id, site.location());
                }
            }
            if (strict && stack.getAmount() > 1) {
                stack.setAmount(1);
                illegalRemoved++;
                return new Inspection(stack, true);
            }
            return new Inspection(stack, false);
        }
        String destination = NestedMaces.destination(stack);
        if (destination == null) {
            return new Inspection(stack, false);
        }
        List<ItemStack> children = NestedMaces.contents(stack);
        boolean changed = false;
        MaceLocation nestedLocation = site.location();
        if (site.possessor() != null) {
            nestedLocation = MaceLocation.playerStorage(stack.getType().name(), site.possessor().getUniqueId());
        }
        for (int index = 0; index < children.size(); index++) {
            int childIndex = index;
            Site childSite = new Site(new Slot(site.key(), index), nestedLocation, null,
                    site.allowed() && plugin.settings().storageAllowed(destination), () -> {
                        List<ItemStack> live = NestedMaces.contents(site.live().get());
                        return childIndex < live.size() ? live.get(childIndex) : null;
                    });
            Inspection child = inspect(children.get(index), childSite, seen, ejected);
            if (child.changed()) {
                children.set(index, child.item());
                changed = true;
            }
        }
        if (changed) {
            NestedMaces.contents(stack, children);
        }
        // One metadata tree in an illegally stacked container represents multiple physical
        // copies. Keep one only when registered maces survived the contents inspection.
        if (plugin.settings().hasMaceLimit() && stack.getAmount() > 1
                && NestedMaces.contains(stack, child -> plugin.registry().contains(plugin.maceItems().trackedId(child)))) {
            stack.setAmount(1);
            illegalRemoved++;
            changed = true;
        }
        return new Inspection(stack, changed);
    }

    private void give(Player player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void release(List<ItemStack> stacks, Location location) {
        if (location.getWorld() != null) {
            for (ItemStack stack : stacks) {
                location.getWorld().dropItemNaturally(location, stack);
            }
        }
    }
}

package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.config.PluginConfig;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Stateful server-thread fixtures: inventory writes change what the next scan actually sees. */
class StorageTestSupport {
    static {
        // InventoryType initializes MenuType on Paper. Supply only those menu lookups;
        // no Minecraft registry or server is needed for inventory transaction tests.
        try (var accessMethod = mockStatic(io.papermc.paper.registry.RegistryAccess.class)) {
            var access = mock(io.papermc.paper.registry.RegistryAccess.class, call -> {
                if (!call.getMethod().getName().equals("getRegistry")) return null;
                boolean menu = call.getArgument(0).toString().toLowerCase(java.util.Locale.ROOT).contains("menu");
                return java.lang.reflect.Proxy.newProxyInstance(org.bukkit.Registry.class.getClassLoader(),
                        new Class<?>[]{org.bukkit.Registry.class}, (proxy, method, args) -> {
                            if (menu && method.getName().startsWith("get")) {
                                return java.lang.reflect.Proxy.newProxyInstance(org.bukkit.inventory.MenuType.Typed.class.getClassLoader(),
                                        new Class<?>[]{org.bukkit.inventory.MenuType.Typed.class},
                                        (value, operation, parameters) -> operation.getName().equals("typed") ? value : null);
                            }
                            return null;
                        });
            });
            accessMethod.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
            InventoryType.values();
        }
    }
    final MaceManagerPlugin plugin = mock(MaceManagerPlugin.class);
    final PluginConfig settings = mock(PluginConfig.class);
    final MaceRegistry registry = mock(MaceRegistry.class);
    final MaceItems items = mock(MaceItems.class);
    final Server server = mock(Server.class);
    final World world = mock(World.class);
    final ScanQueue scans = new ScanQueue();
    final Map<UUID, MaceRecord> records = new LinkedHashMap<>();
    final Map<ItemStack, MaceItems.Classification> classifications = new HashMap<>();
    final List<Player> players = new ArrayList<>();
    final List<Chunk> chunks = new ArrayList<>();
    final List<Item> ground = new ArrayList<>();
    final Map<List<Integer>, Block> blocks = new HashMap<>();
    final Set<String> allowed = new java.util.HashSet<>(Set.of("ender-chests", "shelves", "item-frames", "glow-item-frames"));
    final TrackingListener tracking;

    StorageTestSupport() {
        when(plugin.settings()).thenReturn(settings);
        when(settings.hasMaceLimit()).thenReturn(true);
        when(settings.allowMaceEnchanting()).thenReturn(true);
        when(settings.format(anyString())).thenReturn("Storage message");
        when(settings.storageAllowed(anyString())).thenAnswer(call -> allowed.contains(call.getArgument(0)));
        when(plugin.registry()).thenReturn(registry);
        when(plugin.maceItems()).thenReturn(items);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("StorageTest"));
        when(plugin.scans()).thenReturn(scans);
        when(plugin.chunkAccess()).thenReturn(new ChunkAccess(false));
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getOnlinePlayers()).thenAnswer(call -> players.stream().filter(Player::isOnline).toList());
        when(server.getPlayer(any(UUID.class))).thenAnswer(call -> players.stream()
                .filter(player -> player.isOnline() && player.getUniqueId().equals(call.getArgument(0))).findFirst().orElse(null));
        Block empty = mock(Block.class);
        when(empty.getType()).thenReturn(Material.AIR);
        BlockState air = mock(BlockState.class);
        when(empty.getState()).thenReturn(air);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> blocks.getOrDefault(
                List.of(call.getArgument(0), call.getArgument(1), call.getArgument(2)), empty));
        when(world.getLoadedChunks()).thenAnswer(call -> chunks.toArray(Chunk[]::new));
        when(world.getEntitiesByClass(Item.class)).thenAnswer(call -> List.copyOf(ground));
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> chunks.stream()
                .filter(chunk -> chunk.getX() == (int) call.getArgument(0) && chunk.getZ() == (int) call.getArgument(1))
                .findFirst().orElseThrow(() -> new AssertionError("Would load an unknown chunk")));
        when(items.classify(any())).thenAnswer(call -> classifications.getOrDefault(call.getArgument(0),
                new MaceItems.Classification(false, false, null)));
        when(items.trackedId(any())).thenAnswer(call -> items.classify(call.getArgument(0)).id());
        when(registry.contains(any())).thenAnswer(call -> records.containsKey(call.getArgument(0)));
        when(registry.all()).thenAnswer(call -> List.copyOf(records.values()));
        doAnswer(call -> {
            MaceRecord record = records.get(call.getArgument(0));
            if (record != null) record.setLocation(call.getArgument(1));
            return null;
        }).when(registry).locate(any(), any());
        doAnswer(call -> {
            MaceRecord record = records.get(call.getArgument(0));
            Player player = call.getArgument(1);
            if (record != null) record.setHolder(player.getUniqueId(), player.getName());
            return null;
        }).when(registry).hold(any(), any());
        doAnswer(call -> {
            MaceRecord record = records.get(call.getArgument(0));
            Location location = call.getArgument(1);
            if (record != null) record.markDropped(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
            return null;
        }).when(registry).markDropped(any(), any());
        doAnswer(call -> records.remove(call.getArgument(0))).when(plugin).destroyMace(any());
        tracking = new TrackingListener(plugin);
        when(plugin.tracking()).thenReturn(tracking);
        when(world.dropItemNaturally(any(), any())).thenAnswer(call -> {
            Item item = mock(Item.class);
            ItemStack stack = call.getArgument(1);
            when(item.getItemStack()).thenReturn(stack);
            when(item.getLocation()).thenReturn(call.getArgument(0));
            when(item.getUniqueId()).thenReturn(UUID.randomUUID());
            when(item.isValid()).thenReturn(true);
            ground.add(item);
            tracking.onSpawn(new org.bukkit.event.entity.ItemSpawnEvent(item));
            return item;
        });
    }

    record Inv(Inventory inventory, ItemStack[] slots) {
    }

    Inv inventory(InventoryType type, int size, Location location) {
        Inventory inventory = mock(Inventory.class);
        return inventory(inventory, type, size, location);
    }

    private Inv inventory(Inventory inventory, InventoryType type, int size, Location location) {
        ItemStack[] slots = new ItemStack[size];
        when(inventory.getType()).thenReturn(type);
        when(inventory.getSize()).thenReturn(size);
        when(inventory.getLocation()).thenReturn(location);
        when(inventory.getContents()).thenAnswer(call -> slots.clone());
        doAnswer(call -> {
            ItemStack[] replacement = call.getArgument(0);
            java.util.Arrays.fill(slots, null);
            System.arraycopy(replacement, 0, slots, 0, Math.min(replacement.length, slots.length));
            return null;
        }).when(inventory).setContents(any(ItemStack[].class));
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
        doAnswer(call -> { slots[(int) call.getArgument(0)] = call.getArgument(1); return null; })
                .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            for (int index = 0; index < slots.length; index++) {
                if (slots[index] == null) { slots[index] = stack; return new HashMap<Integer, ItemStack>(); }
            }
            return new HashMap<>(Map.of(0, stack));
        });
        return new Inv(inventory, slots);
    }

    record Person(Player player, Inv inventory, Inv ender, InventoryView view, AtomicReference<ItemStack> cursor) {
    }

    Person player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(at(0, 64, 0));
        when(player.getWorld()).thenReturn(world);
        Inv inventory = inventory(mock(PlayerInventory.class), InventoryType.PLAYER, 41, null);
        Inv ender = inventory(InventoryType.ENDER_CHEST, 27, null);
        InventoryView view = mock(InventoryView.class);
        when(player.getInventory()).thenReturn((PlayerInventory) inventory.inventory());
        when(player.getEnderChest()).thenReturn(ender.inventory());
        when(player.getOpenInventory()).thenReturn(view);
        Inv crafting = inventory(InventoryType.CRAFTING, 5, null);
        when(view.getTopInventory()).thenReturn(crafting.inventory());
        when(view.getBottomInventory()).thenReturn(inventory.inventory());
        when(view.getPlayer()).thenReturn(player);
        AtomicReference<ItemStack> cursor = new AtomicReference<>();
        when(player.getItemOnCursor()).thenAnswer(call -> cursor.get());
        doAnswer(call -> { cursor.set(call.getArgument(0)); return null; }).when(player).setItemOnCursor(nullable(ItemStack.class));
        players.add(player);
        return new Person(player, inventory, ender, view, cursor);
    }

    ItemStack mace(UUID id, UUID owner) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.MACE);
        when(stack.getAmount()).thenReturn(1);
        when(stack.clone()).thenReturn(stack);
        classifications.put(stack, new MaceItems.Classification(true, false, id));
        records.putIfAbsent(id, new MaceRecord(id, owner, "Original"));
        return stack;
    }

    Location at(int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    BlockState blockInventory(Inv inventory, int x, int y, int z) {
        BlockState state = mock(BlockState.class, withSettings().extraInterfaces(InventoryHolder.class));
        Block block = mock(Block.class);
        when(((InventoryHolder) state).getInventory()).thenReturn(inventory.inventory());
        when(state.getBlock()).thenReturn(block);
        when(state.getLocation()).thenReturn(at(x, y, z));
        when(block.getLocation()).thenReturn(at(x, y, z));
        when(block.getState()).thenReturn(state);
        blocks.put(List.of(x, y, z), block);
        return state;
    }

    Chunk chunk(int x, int z, BlockState[] tiles, Entity... entities) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        when(chunk.isLoaded()).thenReturn(true);
        when(chunk.getTileEntities()).thenReturn(tiles);
        when(chunk.getEntities()).thenReturn(entities);
        chunks.add(chunk);
        return chunk;
    }

    void drain() {
        scans.tick(10000, Long.MAX_VALUE);
    }
}

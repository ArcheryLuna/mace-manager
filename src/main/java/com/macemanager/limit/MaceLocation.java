package com.macemanager.limit;

import java.util.Objects;
import java.util.UUID;

/** Persistable location values; resolving them must never force a world or chunk load. */
public record MaceLocation(State state, String world, int chunkX, int chunkZ,
                           Integer blockX, Integer blockY, Integer blockZ,
                           UUID entity, UUID inventoryOwner, String storageType) {
    public enum State {
        CARRIED, STORED, DISPLAYED, DROPPED
    }

    public MaceLocation {
        Objects.requireNonNull(state, "state");
        boolean block = blockX != null && blockY != null && blockZ != null;
        if ((blockX != null || blockY != null || blockZ != null) && !block) {
            throw new IllegalArgumentException("Incomplete block location");
        }
        if (world != null && world.isBlank()) {
            throw new IllegalArgumentException("Blank world");
        }
        if (block && (world == null || chunkX != (blockX >> 4) || chunkZ != (blockZ >> 4))) {
            throw new IllegalArgumentException("Block and chunk location disagree");
        }
        switch (state) {
            case CARRIED -> {
                if (world != null || block || entity != null || inventoryOwner != null || storageType != null
                        || chunkX != 0 || chunkZ != 0) {
                    throw new IllegalArgumentException("Carried mace cannot have a storage location");
                }
            }
            case DROPPED -> {
                if (world == null || block || entity != null || inventoryOwner != null || storageType != null) {
                    throw new IllegalArgumentException("Dropped mace requires only a world and chunk");
                }
            }
            case STORED, DISPLAYED -> {
                if (storageType == null || storageType.isBlank()
                        || (block ? 1 : 0) + (entity != null ? 1 : 0) + (inventoryOwner != null ? 1 : 0) != 1
                        || (inventoryOwner == null && world == null)
                        || (inventoryOwner != null && (world != null || chunkX != 0 || chunkZ != 0))
                        || (state == State.DISPLAYED && inventoryOwner != null)) {
                    throw new IllegalArgumentException("Storage requires a type and one block, entity, or player reference");
                }
            }
        }
    }

    public static MaceLocation carried() {
        return new MaceLocation(State.CARRIED, null, 0, 0, null, null, null, null, null, null);
    }

    public static MaceLocation dropped(String world, int chunkX, int chunkZ) {
        return new MaceLocation(State.DROPPED, world, chunkX, chunkZ, null, null, null, null, null, null);
    }

    public static MaceLocation block(State state, String type, String world, int x, int y, int z) {
        return new MaceLocation(state, world, x >> 4, z >> 4, x, y, z, null, null, type);
    }

    public static MaceLocation entity(State state, String type, String world, int chunkX, int chunkZ, UUID entity) {
        return new MaceLocation(state, world, chunkX, chunkZ, null, null, null,
                Objects.requireNonNull(entity, "entity"), null, type);
    }

    public static MaceLocation playerStorage(String type, UUID player) {
        return new MaceLocation(State.STORED, null, 0, 0, null, null, null, null,
                Objects.requireNonNull(player, "player"), type);
    }
}

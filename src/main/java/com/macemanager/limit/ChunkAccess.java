package com.macemanager.limit;

import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.lang.reflect.Method;

/** Optional Paper snapshot-free calls, with no Paper method linkage on Spigot. */
public final class ChunkAccess {
    private static final Method TILES = method(Chunk.class, "getTileEntities");
    private static final Method STATE = method(Block.class, "getState");
    private boolean direct;

    public ChunkAccess(boolean direct) {
        this.direct = direct;
    }

    public BlockState[] tiles(Chunk chunk) {
        if (direct && TILES != null) {
            try {
                return (BlockState[]) TILES.invoke(chunk, false);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                direct = false;
            }
        }
        return chunk.getTileEntities();
    }

    public BlockState state(Block block) {
        if (direct && STATE != null) {
            try {
                return (BlockState) STATE.invoke(block, false);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                direct = false;
            }
        }
        return block.getState();
    }

    private static Method method(Class<?> type, String name) {
        try {
            return type.getMethod(name, boolean.class);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }
}

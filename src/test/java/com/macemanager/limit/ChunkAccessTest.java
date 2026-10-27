package com.macemanager.limit;

import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChunkAccessTest {
    @Test
    void directQueryRequestsLiveStatesOnlyWhenEnabled() {
        BlockState[] states = new BlockState[0];
        Chunk chunk = chunk((proxy, method, args) -> {
            assertEquals("getTileEntities", method.getName());
            assertArrayEquals(new Object[]{false}, args);
            return states;
        });
        assertSame(states, new ChunkAccess(true).tiles(chunk));

        Chunk fallback = chunk((proxy, method, args) -> {
            assertEquals(0, method.getParameterCount());
            return states;
        });
        assertSame(states, new ChunkAccess(false).tiles(fallback));
    }

    @Test
    void failingOptionalMethodFallsBackAndIsNotRetried() {
        AtomicInteger direct = new AtomicInteger();
        AtomicInteger fallback = new AtomicInteger();
        BlockState[] states = new BlockState[0];
        Chunk chunk = chunk((proxy, method, args) -> {
            if (method.getParameterCount() == 1) {
                direct.incrementAndGet();
                throw new UnsupportedOperationException("Optional API unavailable");
            }
            fallback.incrementAndGet();
            return states;
        });
        ChunkAccess access = new ChunkAccess(true);
        assertSame(states, access.tiles(chunk));
        assertSame(states, access.tiles(chunk));
        assertEquals(1, direct.get());
        assertEquals(2, fallback.get());
    }

    private Chunk chunk(java.lang.reflect.InvocationHandler handler) {
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class}, handler);
    }
}

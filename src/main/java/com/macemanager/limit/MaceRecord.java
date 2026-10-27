package com.macemanager.limit;

import java.util.UUID;

public final class MaceRecord {
    private final UUID id;
    private UUID holder;
    private String holderName;
    private boolean dropped;
    private String dropWorld;
    private int dropChunkX;
    private int dropChunkZ;

    public MaceRecord(UUID id, UUID holder, String holderName) {
        this.id = id;
        this.holder = holder;
        this.holderName = holderName == null || holderName.isBlank() ? "unknown" : holderName;
    }

    public UUID id() {
        return id;
    }

    public UUID holder() {
        return holder;
    }

    public String holderName() {
        return holderName;
    }

    public boolean dropped() {
        return dropped;
    }

    public String dropWorld() {
        return dropWorld;
    }

    public int dropChunkX() {
        return dropChunkX;
    }

    public int dropChunkZ() {
        return dropChunkZ;
    }

    public boolean setHolder(UUID holder, String holderName) {
        String name = holderName == null || holderName.isBlank() ? "unknown" : holderName;
        boolean changed = dropped || this.holder == null || !holder.equals(this.holder) || !name.equals(this.holderName);
        this.holder = holder;
        this.holderName = name;
        this.dropped = false;
        this.dropWorld = null;
        return changed;
    }

    public boolean markDropped(String world, int chunkX, int chunkZ) {
        boolean changed = !dropped || world == null || !world.equals(dropWorld) || chunkX != dropChunkX || chunkZ != dropChunkZ;
        this.dropped = true;
        this.dropWorld = world;
        this.dropChunkX = chunkX;
        this.dropChunkZ = chunkZ;
        return changed;
    }

    public void setDrop(String world, int chunkX, int chunkZ) {
        this.dropped = world != null;
        this.dropWorld = world;
        this.dropChunkX = chunkX;
        this.dropChunkZ = chunkZ;
    }
}

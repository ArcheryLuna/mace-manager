package com.macemanager.limit;

import java.util.Objects;
import java.util.UUID;

public final class MaceRecord {
    private final UUID id;
    private UUID holder;
    private String holderName;
    private MaceLocation location = MaceLocation.carried();

    public MaceRecord(UUID id, UUID holder, String holderName) {
        this.id = id;
        this.holder = holder;
        this.holderName = holderName == null || holderName.isBlank() ? "unknown" : holderName;
    }

    public UUID id() {
        return id;
    }

    /** Recorded owner/last possessor, retained independently of the physical location. */
    public UUID holder() {
        return holder;
    }

    public String holderName() {
        return holderName;
    }

    public boolean dropped() {
        return location.state() == MaceLocation.State.DROPPED;
    }

    public String dropWorld() {
        return dropped() ? location.world() : null;
    }

    public int dropChunkX() {
        return dropped() ? location.chunkX() : 0;
    }

    public int dropChunkZ() {
        return dropped() ? location.chunkZ() : 0;
    }

    /** Call only for actual player possession, not when viewing a stored mace. */
    public boolean setHolder(UUID holder, String holderName) {
        String name = holderName == null || holderName.isBlank() ? "unknown" : holderName;
        boolean changed = location.state() != MaceLocation.State.CARRIED || this.holder == null
                || !holder.equals(this.holder) || !name.equals(this.holderName);
        this.holder = holder;
        this.holderName = name;
        this.location = MaceLocation.carried();
        return changed;
    }

    public MaceLocation location() {
        return location;
    }

    /** Changes physical location without transferring the recorded ownership. */
    public boolean setLocation(MaceLocation location) {
        Objects.requireNonNull(location, "location");
        if (this.location.equals(location)) {
            return false;
        }
        this.location = location;
        return true;
    }

    public boolean markDropped(String world, int chunkX, int chunkZ) {
        return setLocation(MaceLocation.dropped(world, chunkX, chunkZ));
    }

    public void setDrop(String world, int chunkX, int chunkZ) {
        setLocation(world == null ? MaceLocation.carried() : MaceLocation.dropped(world, chunkX, chunkZ));
    }
}

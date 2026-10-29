package com.macemanager.limit;

import java.util.Objects;
import java.util.UUID;

/** Immutable values captured on the server thread for persistence. */
record MaceSnapshot(UUID id, UUID holder, String holderName, MaceLocation location) {
    MaceSnapshot {
        Objects.requireNonNull(location, "location");
    }

    MaceSnapshot(UUID id, UUID holder, String holderName, boolean dropped,
                 String dropWorld, int dropChunkX, int dropChunkZ) {
        this(id, holder, holderName, dropped ? MaceLocation.dropped(dropWorld, dropChunkX, dropChunkZ)
                : MaceLocation.carried());
    }

    static MaceSnapshot from(MaceRecord record) {
        return new MaceSnapshot(record.id(), record.holder(), record.holderName(), record.location());
    }

    boolean dropped() {
        return location.state() == MaceLocation.State.DROPPED;
    }

    String dropWorld() {
        return dropped() ? location.world() : null;
    }

    int dropChunkX() {
        return dropped() ? location.chunkX() : 0;
    }

    int dropChunkZ() {
        return dropped() ? location.chunkZ() : 0;
    }

    MaceRecord record() {
        MaceRecord record = new MaceRecord(id, holder, holderName);
        record.setLocation(location);
        return record;
    }
}

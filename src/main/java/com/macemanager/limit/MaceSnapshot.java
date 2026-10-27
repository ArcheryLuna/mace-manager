package com.macemanager.limit;

import java.util.UUID;

/** Immutable values captured on the server thread for persistence. */
record MaceSnapshot(UUID id, UUID holder, String holderName, boolean dropped,
                    String dropWorld, int dropChunkX, int dropChunkZ) {
    static MaceSnapshot from(MaceRecord record) {
        return new MaceSnapshot(record.id(), record.holder(), record.holderName(), record.dropped(),
                record.dropWorld(), record.dropChunkX(), record.dropChunkZ());
    }

    MaceRecord record() {
        MaceRecord record = new MaceRecord(id, holder, holderName);
        if (dropped) {
            record.setDrop(dropWorld, dropChunkX, dropChunkZ);
        }
        return record;
    }
}

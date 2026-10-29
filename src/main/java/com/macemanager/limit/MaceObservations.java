package com.macemanager.limit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One server-thread scan pass. Rechecks old slots because items can move between units. */
final class MaceObservations {
    private record Witness(Object slot, BooleanSupplier present) {
    }

    private final Map<UUID, Witness> witnesses = new HashMap<>();

    boolean claim(UUID id, Object slot, BooleanSupplier present) {
        Witness previous = witnesses.get(id);
        if (previous != null && !previous.slot().equals(slot) && previous.present().getAsBoolean()) {
            return false;
        }
        witnesses.put(id, new Witness(slot, present));
        return true;
    }

    boolean contains(UUID id) {
        Witness witness = witnesses.get(id);
        return witness != null && witness.present().getAsBoolean();
    }
}

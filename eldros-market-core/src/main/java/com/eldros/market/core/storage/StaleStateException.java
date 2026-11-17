package com.eldros.market.core.storage;

/**
 * A compare-and-set update found a row in a different state than expected (docs/03 §7.6). The surrounding
 * transaction is rolled back; nothing was applied. Usually means the engine's view and the database diverged.
 */
public class StaleStateException extends StorageException {

    public StaleStateException(String message) {
        super(message);
    }
}

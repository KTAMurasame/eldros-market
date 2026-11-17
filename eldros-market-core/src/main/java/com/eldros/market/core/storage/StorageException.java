package com.eldros.market.core.storage;

/** Unchecked wrapper for storage failures; callers decide whether to retry or enter safe mode (docs/03 §7.8). */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

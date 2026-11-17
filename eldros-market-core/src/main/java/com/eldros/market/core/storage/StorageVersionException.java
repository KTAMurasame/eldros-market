package com.eldros.market.core.storage;

/** The SQLite engine provided by the server is older than 3.47 (D10). Market stays closed. */
public class StorageVersionException extends StorageException {

    public StorageVersionException(String message) {
        super(message);
    }
}

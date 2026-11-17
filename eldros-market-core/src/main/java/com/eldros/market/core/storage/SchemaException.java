package com.eldros.market.core.storage;

/** The database schema cannot be used by this plugin version (edited migration, newer schema). Market stays closed. */
public class SchemaException extends StorageException {

    public SchemaException(String message) {
        super(message);
    }
}

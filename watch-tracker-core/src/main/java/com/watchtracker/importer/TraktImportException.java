package com.watchtracker.importer;

/** Raised when Trakt history cannot be read or authenticated. */
public final class TraktImportException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public TraktImportException(String message) {
        super(message);
    }

    public TraktImportException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.watchtracker.exception;

/** Signals an invalid or unreadable Netflix viewing-history export. */
public final class NetflixCsvException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a clear import failure message.
     *
     * @param message import failure description
     */
    public NetflixCsvException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the original I/O cause.
     *
     * @param message import failure description
     * @param cause underlying failure
     */
    public NetflixCsvException(String message, Throwable cause) {
        super(message, cause);
    }
}

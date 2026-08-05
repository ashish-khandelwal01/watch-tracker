package com.watchtracker.model;

/** Optional classification that must be supplied by a trusted source, never guessed. */
public enum ShowType {
    /** No trusted classification is available. */
    UNKNOWN,
    /** A feature-length film. */
    MOVIE,
    /** A multi-season television series. */
    SERIES,
    /** A finite television series. */
    LIMITED_SERIES,
    /** An animated series. */
    ANIME,
    /** A documentary programme or series. */
    DOCUMENTARY
}

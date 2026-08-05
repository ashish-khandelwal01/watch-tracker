package com.watchtracker.parser;

import com.watchtracker.exception.NetflixCsvException;
import com.watchtracker.model.WatchEvent;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses the two-column Netflix viewing-history CSV format without normalizing titles. */
public final class NetflixCsvParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetflixCsvParser.class);
    private static final String TITLE = "Title";
    private static final String DATE = "Date";
    private static final String NETFLIX = "NETFLIX";
    private static final DateTimeFormatter NETFLIX_DATE = DateTimeFormatter.ofPattern("M/d/uu");

    /**
     * Parses a UTF-8 Netflix export into raw watch events.
     *
     * @param csv Netflix viewing-history CSV path
     * @return immutable raw watch events in CSV order
     * @throws NetflixCsvException if the file cannot be read or is malformed
     */
    public List<WatchEvent> parse(Path csv) {
        if (csv == null || !Files.isRegularFile(csv)) {
            throw new NetflixCsvException("CSV file does not exist: " + csv);
        }
        try (Reader reader = Files.newBufferedReader(csv);
                CSVParser parser = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreSurroundingSpaces(true)
                        .setTrim(true)
                        .get()
                        .parse(reader)) {
            validateHeaders(parser);
            List<WatchEvent> events = new ArrayList<>();
            for (CSVRecord row : parser) {
                if (row.get(TITLE) == null || row.get(TITLE).isBlank()) {
                    LOGGER.warn("Skipping row {} with a blank Title", row.getRecordNumber());
                    continue;
                }
                events.add(WatchEvent.raw(
                        required(row, TITLE), parseDate(required(row, DATE), row.getRecordNumber()), NETFLIX));
            }
            return List.copyOf(events);
        } catch (IOException | IllegalArgumentException exception) {
            throw new NetflixCsvException("Unable to parse Netflix CSV: " + csv, exception);
        }
    }

    /** Ensures the CSV exposes the required Netflix headers. */
    private static void validateHeaders(CSVParser parser) {
        if (!parser.getHeaderMap().containsKey(TITLE) || !parser.getHeaderMap().containsKey(DATE)) {
            throw new NetflixCsvException("Netflix CSV must contain Title and Date headers");
        }
    }

    /** Reads a mandatory non-blank field from one CSV row. */
    private static String required(CSVRecord row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new NetflixCsvException("Row " + row.getRecordNumber() + " has a blank " + column);
        }
        return value;
    }

    /** Parses either ISO or current Netflix US-style export dates. */
    private static LocalDateTime parseDate(String value, long rowNumber) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (DateTimeParseException exception) {
            try {
                return LocalDate.parse(value, NETFLIX_DATE).atStartOfDay();
            } catch (DateTimeParseException netflixException) {
                throw new NetflixCsvException("Row " + rowNumber + " has an invalid date: " + value, netflixException);
            }
        }
    }
}

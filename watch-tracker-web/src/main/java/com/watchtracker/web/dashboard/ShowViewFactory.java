package com.watchtracker.web.dashboard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.ShowMetadata;
import com.watchtracker.metadata.WatchProgress;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import com.watchtracker.web.persistence.EnrichedShowDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Builds {@link ShowView} from Mongo documents (payload = full EnrichedShow JSON). */
@Component
public class ShowViewFactory {
    private static final String TMDB_IMAGE = "https://image.tmdb.org/t/p/";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ShowView fromDocument(EnrichedShowDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        EnrichedShow enriched = parse(document);
        if (enriched == null) {
            return fallback(document);
        }
        Show show = enriched.show();
        ShowMetadata meta = enriched.metadata().orElse(null);
        WatchProgress progress = enriched.progress().orElse(null);

        String posterPath = meta != null ? meta.posterPath() : null;
        String small = posterUrl(posterPath, "w185");
        String large = posterUrl(posterPath, "w500");

        String type = meta != null && meta.type() != null
                ? meta.type().name()
                : (show.type() != null ? show.type().name() : null);

        return new ShowView(
                document.id(),
                show.title(),
                show.aliases() == null ? List.of() : List.copyOf(show.aliases()),
                document.matchConfidence(),
                type,
                small,
                large,
                meta != null ? meta.overview() : null,
                meta != null && meta.genres() != null ? List.copyOf(meta.genres()) : List.of(),
                meta != null && meta.releaseDate() != null ? meta.releaseDate().toString() : null,
                meta != null ? meta.provider() : null,
                meta != null ? meta.providerId() : null,
                progressSummary(show, progress, type),
                progress != null ? progress.season() : null,
                progress != null ? progress.episode() : null,
                progress != null && progress.status() != null ? progress.status().name() : null,
                show.watchCount(),
                show.firstWatched() != null ? show.firstWatched().toString() : null,
                show.lastWatched() != null ? show.lastWatched().toString() : null,
                show.highestSeasonSeen(),
                show.highestEpisodeSeen());
    }

    public List<ShowView> fromDocuments(List<EnrichedShowDocument> documents) {
        List<ShowView> views = new ArrayList<>(documents.size());
        for (EnrichedShowDocument document : documents) {
            views.add(fromDocument(document));
        }
        return views;
    }

    private EnrichedShow parse(EnrichedShowDocument document) {
        if (document.payload() == null || document.payload().isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(document.payload(), EnrichedShow.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private static ShowView fallback(EnrichedShowDocument document) {
        return new ShowView(
                document.id(),
                document.title(),
                document.aliases() == null ? List.of() : document.aliases(),
                document.matchConfidence(),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                "—",
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null);
    }

    private static String posterUrl(String posterPath, String size) {
        if (posterPath == null || posterPath.isBlank()) {
            return null;
        }
        String path = posterPath.startsWith("/") ? posterPath : "/" + posterPath;
        return TMDB_IMAGE + size + path;
    }

    private static String progressSummary(Show show, WatchProgress progress, String type) {
        if (progress != null) {
            if (progress.type() == ShowType.MOVIE
                    || (type != null && "MOVIE".equals(type))) {
                return progress.status() != null ? progress.status().name() : "Movie";
            }
            if (progress.season() != null && progress.episode() != null) {
                String base = "S" + progress.season() + " E" + progress.episode();
                if (progress.status() != null) {
                    return base + " · " + progress.status().name();
                }
                return base;
            }
        }
        if (type != null && "MOVIE".equals(type)) {
            return "Movie";
        }
        if (show.highestSeasonSeen() != null || show.highestEpisodeSeen() != null) {
            return "S"
                    + (show.highestSeasonSeen() != null ? show.highestSeasonSeen() : "?")
                    + " E"
                    + (show.highestEpisodeSeen() != null ? show.highestEpisodeSeen() : "?")
                    + " (from titles)";
        }
        return "—";
    }
}

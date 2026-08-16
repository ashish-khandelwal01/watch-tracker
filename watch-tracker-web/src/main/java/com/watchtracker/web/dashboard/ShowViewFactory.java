package com.watchtracker.web.dashboard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.ReviewItem;
import com.watchtracker.metadata.ReviewQueueBuilder;
import com.watchtracker.metadata.ReviewReason;
import com.watchtracker.metadata.ShowMetadata;
import com.watchtracker.metadata.WatchProgress;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import com.watchtracker.web.persistence.EnrichedShowDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds {@link ShowView} from Mongo documents; review reasons match CLI ReviewQueueBuilder. */
@Component
public class ShowViewFactory {
    private static final String TMDB_IMAGE = "https://image.tmdb.org/t/p/";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public List<ShowView> fromDocuments(List<EnrichedShowDocument> documents) {
        List<EnrichedShow> enriched = new ArrayList<>();
        List<EnrichedShowDocument> order = new ArrayList<>();
        for (EnrichedShowDocument document : documents) {
            EnrichedShow e = parse(document);
            if (e != null) {
                enriched.add(e);
                order.add(document);
            } else {
                enriched.add(null);
                order.add(document);
            }
        }

        Map<String, List<String>> reasonsByShowId = new HashMap<>();
        List<EnrichedShow> forReview = enriched.stream().filter(Objects::nonNull).toList();
        for (ReviewItem item : ReviewQueueBuilder.build(forReview)) {
            reasonsByShowId.put(
                    item.showId().toString(),
                    item.reasons().stream().map(ReviewReason::name).toList());
        }

        List<ShowView> views = new ArrayList<>(documents.size());
        for (int i = 0; i < order.size(); i++) {
            EnrichedShowDocument document = order.get(i);
            EnrichedShow e = enriched.get(i);
            List<String> reasons = reasonsByShowId.getOrDefault(document.id(), List.of());
            views.add(e == null ? fallback(document, reasons) : toView(document, e, reasons));
        }
        return views;
    }

    public ShowView fromDocument(EnrichedShowDocument document) {
        return fromDocuments(List.of(document)).get(0);
    }

    private ShowView toView(EnrichedShowDocument document, EnrichedShow enriched, List<String> reasons) {
        Show show = enriched.show();
        ShowMetadata meta = enriched.metadata().orElse(null);
        WatchProgress progress = enriched.progress().orElse(null);

        String posterPath = meta != null ? meta.posterPath() : null;
        String type = meta != null && meta.type() != null
                ? meta.type().name()
                : (show.type() != null ? show.type().name() : null);

        return new ShowView(
                document.id(),
                show.title(),
                show.aliases() == null ? List.of() : List.copyOf(show.aliases()),
                document.matchConfidence(),
                type,
                posterUrl(posterPath, "w185"),
                posterUrl(posterPath, "w500"),
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
                show.highestEpisodeSeen(),
                reasons);
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

    private static ShowView fallback(EnrichedShowDocument document, List<String> reasons) {
        List<String> r = reasons.isEmpty() && document.matchConfidence() == null
                ? List.of("NO_METADATA_MATCH")
                : reasons;
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
                null,
                r);
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
            if (progress.type() == ShowType.MOVIE || (type != null && "MOVIE".equals(type))) {
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

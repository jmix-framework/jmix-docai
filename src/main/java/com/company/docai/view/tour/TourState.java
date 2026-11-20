package com.company.docai.view.tour;

import org.jspecify.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The progress of the tour in a session; the cookie keeps the status and the chapters done.
 */
public class TourState implements Serializable {

    /**
     * The name of the cookie with the progress of the tour.
     */
    public static final String COOKIE_NAME = "DOCAI_TOUR";

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Pattern COOKIE_VALUE = Pattern.compile("v1\\.([nafe])\\.(0|[1-5]+)");

    private Status status = Status.NOT_STARTED;
    private final Set<TourChapter> done = EnumSet.noneOf(TourChapter.class);
    private final Set<TourChapter> skipped = EnumSet.noneOf(TourChapter.class);
    private final Set<TourChapter> explained = EnumSet.noneOf(TourChapter.class);
    @Nullable
    private UUID uploadedDocumentId;

    /**
     * Reads the progress from the value of the cookie; a missing or unknown value is a tour not begun.
     */
    public static TourState fromCookie(@Nullable String value) {
        TourState state = new TourState();

        if (value == null) {
            return state;
        }

        Matcher matcher = COOKIE_VALUE.matcher(value);

        if (!matcher.matches()) {
            return state;
        }

        state.status = Status.ofCode(matcher.group(1).charAt(0));

        matcher.group(2)
                .chars()
                .forEach(digit -> TourChapter.ofNumber(digit - '0').ifPresent(state.done::add));

        return state;
    }

    /**
     * Returns the progress of a main view being built: the session's, or the cookie's at its first main view.
     */
    public static TourState of(@Nullable TourState sessionState, @Nullable String cookie) {
        return sessionState != null ? sessionState : fromCookie(cookie);
    }

    /**
     * Returns whether the browser cookie has to be written anew.
     */
    public boolean differsFrom(@Nullable String cookie) {
        return !cookieValue().equals(cookie);
    }

    /**
     * Returns the canonical value of the cookie for this progress, as {@code v1.a.25}.
     */
    public String cookieValue() {
        String chapters = done.isEmpty()
                ? "0"
                : done.stream()
                        .map(chapter -> String.valueOf(chapter.getNumber()))
                        .collect(Collectors.joining());

        return "v1." + status.code + "." + chapters;
    }

    /**
     * Returns whether Doc greets at the sign-in by himself.
     */
    public boolean greetsByItself(boolean autoOpen, boolean aiAvailable) {
        return status == Status.NOT_STARTED && autoOpen && aiAvailable;
    }

    /**
     * Returns the status of the tour.
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Begins the tour; a tour that runs or is over stays as it is.
     */
    public void start() {
        if (status == Status.NOT_STARTED) {
            status = Status.RUNNING;
        }
    }

    /**
     * Ends the tour early.
     */
    public void end() {
        status = Status.ENDED;
    }

    /**
     * Marks the tour as passed: the finale is shown.
     */
    public void finish() {
        status = Status.FINISHED;
    }

    /**
     * Begins everything anew from the user menu.
     */
    public void restart() {
        status = Status.NOT_STARTED;
        done.clear();
        skipped.clear();
        explained.clear();
        uploadedDocumentId = null;
    }

    /**
     * Marks the chapter as done; returns whether it is a new mark.
     */
    public boolean markDone(TourChapter chapter) {
        if (status == Status.FINISHED || status == Status.ENDED) {
            return false;
        }

        return done.add(chapter);
    }

    /**
     * Skips the chapter in this pass; it stays not done.
     */
    public void skip(TourChapter chapter) {
        skipped.add(chapter);
    }

    /**
     * Notes that the explanation of the chapter is shown; returns whether this is the first time in this pass.
     */
    public boolean markExplained(TourChapter chapter) {
        return explained.add(chapter);
    }

    /**
     * Remembers the document that did the first chapter for the next one; the cookie does not keep it, as the ids
     * of a stand change with every sign-in.
     */
    public void rememberUploaded(UUID documentId) {
        uploadedDocumentId = documentId;
    }

    /**
     * Returns the id of the document uploaded in this pass, if the session remembers one.
     */
    public Optional<UUID> getUploadedDocumentId() {
        return Optional.ofNullable(uploadedDocumentId);
    }

    /**
     * Returns whether the chapter is done.
     */
    public boolean isDone(TourChapter chapter) {
        return done.contains(chapter);
    }

    /**
     * Returns how many chapters are done.
     */
    public int getDoneCount() {
        return done.size();
    }

    /**
     * Returns the first chapter that is neither done nor skipped and is available; none means the finale.
     */
    public Optional<TourChapter> next(Predicate<TourChapter> available) {
        return Arrays.stream(TourChapter.values())
                .filter(chapter -> !done.contains(chapter) && !skipped.contains(chapter))
                .filter(available)
                .findFirst();
    }

    /**
     * The status of the tour, with its letter in the cookie.
     */
    public enum Status {

        NOT_STARTED('n'),
        RUNNING('a'),
        FINISHED('f'),
        ENDED('e');

        private final char code;

        Status(char code) {
            this.code = code;
        }

        private static Status ofCode(char code) {
            return Arrays.stream(values())
                    .filter(status -> status.code == code)
                    .findFirst()
                    .orElseThrow();
        }
    }
}

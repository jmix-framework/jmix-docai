package com.company.docai.view.tour;

import java.util.Arrays;
import java.util.Optional;

/**
 * The chapters of the tour in the order of its story; the number is the one the cookie keeps.
 */
public enum TourChapter {

    UPLOAD(1, false),
    FILL_WITH_AI(2, true),
    SEARCH_BY_MEANING(3, true),
    DOCUMENT_ASSISTANT(4, true),
    AI_ASSISTANT(5, true);

    private final int number;
    private final boolean aiNeeded;

    TourChapter(int number, boolean aiNeeded) {
        this.number = number;
        this.aiNeeded = aiNeeded;
    }

    /**
     * Returns the number of the chapter, from 1 to 5.
     */
    public int getNumber() {
        return number;
    }

    /**
     * Returns whether the chapter needs the AI.
     */
    public boolean isAiNeeded() {
        return aiNeeded;
    }

    /**
     * Returns the key of the title of the chapter in the messages of the tour.
     */
    public String getTitleKey() {
        return "chapter" + number + ".title";
    }

    /**
     * Returns the chapter with the number, if there is one.
     */
    public static Optional<TourChapter> ofNumber(int number) {
        return Arrays.stream(values())
                .filter(chapter -> chapter.number == number)
                .findFirst();
    }
}

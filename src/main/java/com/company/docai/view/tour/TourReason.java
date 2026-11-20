package com.company.docai.view.tour;

/**
 * Why a chapter of the tour is unavailable: the reason under its title and the note of Doc.
 */
public enum TourReason {

    AI_OFF("reason.ai", "aiOff.text"),
    NO_ASSISTANT("reason.access", "unavailable.assistant"),
    NO_FOLDER_ACCESS("reason.access", "unavailable.folder"),
    MISSING_DOCUMENT("reason.missing", "unavailable.document");

    private final String reasonKey;
    private final String noteKey;

    TourReason(String reasonKey, String noteKey) {
        this.reasonKey = reasonKey;
        this.noteKey = noteKey;
    }

    /**
     * Returns the key of the short reason shown under the title of the chapter.
     */
    public String getReasonKey() {
        return reasonKey;
    }

    /**
     * Returns the key of what Doc says about it.
     */
    public String getNoteKey() {
        return noteKey;
    }
}

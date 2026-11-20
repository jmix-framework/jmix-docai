package com.company.docai.view.tour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text of the tour is in both bundles: the tour reads them by its classes, which the check of the message
 * references does not see.
 */
class TourMessagesTest {

    private static final String TOUR = "com.company.docai.view.tour/";
    private static final String PANEL = "com.company.docai.app.ui.component.tour/";

    private static final List<String> KEYS = List.of(
            TOUR + "greeting.text", TOUR + "home.text", TOUR + "scanCard.text", TOUR + "uploadedCard.text",
            TOUR + "fillWithAi.text",
            TOUR + "searchByMeaning.text", TOUR + "searchByMeaning.fieldText", TOUR + "leaseCard.text",
            TOUR + "leaseAssistant.text", TOUR + "assistantMenu.text", TOUR + "assistantQuestion.text",
            TOUR + "repository.text", TOUR + "upload.text", TOUR + "finale.text", TOUR + "finale.noAiText",
            TOUR + "chapter1.title", TOUR + "chapter2.title", TOUR + "chapter3.title", TOUR + "chapter4.title",
            TOUR + "chapter5.title", TOUR + "reason.ai", TOUR + "reason.access", TOUR + "reason.missing",
            TOUR + "startButton", TOUR + "skipButton", TOUR + "nextButton", TOUR + "gotItButton",
            TOUR + "skipTaskButton", TOUR + "finishButton", TOUR + "showAgainButton", TOUR + "uploadSampleButton",
            TOUR + "taskDone.title",
            TOUR + "taskDone.next", TOUR + "showAgain.text", TOUR + "unavailable.document",
            TOUR + "unavailable.folder", TOUR + "unavailable.assistant", TOUR + "aiOff.text", TOUR + "goodbye.text",
            TOUR + "popover.ariaLabel", TOUR + "sample.ariaLabel",
            PANEL + "panel.title", PANEL + "panel.progress", PANEL + "panel.endButton", PANEL + "chapter.showButton",
            PANEL + "doc.progress", PANEL + "panel.ariaLabel", PANEL + "doc.ariaLabel",
            PANEL + "collapseButton.ariaLabel", PANEL + "closeButton.ariaLabel", PANEL + "showButton.ariaLabel",
            PANEL + "chapter.doneLabel",
            "com.company.docai.view.main/tourAction.text");

    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry("chapter1.title", "Upload a document"),
            Map.entry("chapter2.title", "Fill in a document with AI"),
            Map.entry("chapter3.title", "Search by meaning"),
            Map.entry("chapter4.title", "Ask AI about a contract"),
            Map.entry("chapter5.title", "Ask AI about all your documents"),
            Map.entry("upload.text", "Drag this invoice into the table or click Upload it: it becomes a document in"
                    + " this folder."),
            Map.entry("uploadSampleButton", "Upload it"),
            Map.entry("sample.ariaLabel", "%s, an invoice to drag into the table"),
            Map.entry("uploadedCard.text", "This is the card of the document you uploaded: its fields, versions,"
                    + " comments and a preview of the file. It has no type or fields yet."),
            Map.entry("fillWithAi.text", "Click Fill with AI: the AI reads the file and fills in the type, the fields,"
                    + " the date and the tags. The filled fields are marked with a wand: check them and save."),
            Map.entry("aiOff.text", "AI is turned off on this server, so the AI chapters are unavailable. You can"
                    + " upload a document without it."));

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.properties", "messages_ru.properties"})
    void everyTextOfTheTourIsInTheBundle(String bundle) throws IOException {
        Properties messages = bundle(bundle);

        assertThat(KEYS).allSatisfy(key -> assertThat(messages.getProperty(key)).as(key).isNotBlank());
    }

    @Test
    void theEnglishTextsOfTheChaptersAndTheUploadAreThoseOfTheDesign() throws IOException {
        Properties messages = bundle("messages_en.properties");

        assertThat(ENGLISH).allSatisfy((key, text) -> assertThat(messages.getProperty(TOUR + key)).as(key)
                .isEqualTo(text));
    }

    @Test
    void theRussianTextsOfTheChaptersAndTheUploadDifferFromTheEnglishOnes() throws IOException {
        // the tests hold no Cyrillic, so each Russian text is checked against the English one
        Properties messages = bundle("messages_ru.properties");

        assertThat(ENGLISH).allSatisfy((key, text) -> assertThat(messages.getProperty(TOUR + key)).as(key)
                .isNotBlank()
                .isNotEqualTo(text));
        assertThat(messages.getProperty(TOUR + "sample.ariaLabel")).startsWith("%s");
    }

    private Properties bundle(String bundle) throws IOException {
        Properties messages = new Properties();

        String path = "/com/company/docai/" + bundle;

        try (InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream(path));
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            messages.load(reader);
        }

        return messages;
    }
}

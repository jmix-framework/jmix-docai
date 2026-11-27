package com.company.docai.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DemoDataCheckTest {

    @Autowired
    private DemoDataLoader loader;

    @Test
    void everyMistakeOfADescriptionIsListed() {
        assertThat(loader.check("broken"))
                .containsExactlyInAnyOrder(
                        "users: there is no user 'nobody'",
                        "statuses: the status 'LOST' moves to no status 'NOWHERE'",
                        "statuses: the new status 'NAMELESS' needs a name and a color",
                        "statuses: the color of the status 'ACTIVE' is not #rrggbb",
                        "statuses: the status 'DRAFT' is given to new documents and cannot lock them",
                        "statuses: the status 'LOST' cannot be reached from the initial status",
                        "statuses: the status 'NAMELESS' cannot be reached from the initial status",
                        "statuses: the status 'ARCHIVED' cannot be reached from the initial status",
                        "/Broken: there is no user 'nobody'",
                        "/Broken: there is no role 'no-such-role'",
                        "/Broken: an access entry must name a user or a role",
                        "/Broken: there is no access level 'OWN'",
                        "/Broken/Twice: two documents have this name",
                        "/Broken/No versions: the document has no versions",
                        "/Broken/Missing file: there is no file 'missing.txt'",
                        "/Broken/Text without an extension: the file name 'note' has no extension",
                        "/Broken/Unknown author: there is no user 'nobody'",
                        "/Broken/Unknown status: there is no status 'SIGNED'",
                        "/Broken/Unknown type: there is no type 'Receipt'",
                        "/Broken/Unknown field: the type does not accept colour: red",
                        "/Broken/Wrong value: the type does not accept Amount: a lot",
                        "/Broken/Fields without a type: the fields need a type",
                        "/Broken/Unknown deleter: there is no user 'nobody'",
                        "/Broken/Deleted at no time: the deletion has no time",
                        "/Broken/Unknown commenter: there is no user 'nobody'",
                        "/Broken/Empty comment: a comment has no text",
                        "/Broken/Comment at no time: a comment has no time",
                        "/Broken/Edited before written: a comment is edited before it is written",
                        "/Broken/Long comment: a comment is longer than 2000 characters",
                        "/Broken/Uploaded in the future: a version is uploaded in the future, 2099-01-01T10:00",
                        "/Broken/Uploaded before its date: the last version is uploaded before the date of the"
                                + " document",
                        "/Broken/Tag in another case: the tag 'LEASE' is written 'Lease' elsewhere",
                        "/Broken/Empty tag: a tag has no name",
                        "/Broken/Long tag: the tag '%s' is longer than 50 characters".formatted("x".repeat(51)),
                        "/Broken: two folders have this name",
                        "/: a folder has no name");
    }

    // the dates come first, and a set whose dates cannot be computed lists only their mistakes
    @Test
    void everyMistakeOfTheDatesOfADescriptionIsListed() {
        assertThat(loader.check("dates-broken"))
                .containsExactlyInAnyOrder(
                        "/Dates {year:-1}/Undeclared date: 'issued' is not a date of the document",
                        "/Dates {year:-1}/Unreadable date: the date 'issued' is not an expression: 'next-week'",
                        "/Dates {year:-1}/Dates in a circle: the dates 'start', 'end' refer to each other",
                        "/Dates {year:-1}/Unreadable time: '-1d 25:00' is not a time",
                        "/Dates {year:-1}/Field of an undeclared date: the field 'dueDate' refers to 'due', which the"
                                + " document does not declare",
                        "/Dates {year:-1}/Missing text: there is no text 'missing.txt'",
                        "/Dates {year:-1}/Text of an undeclared date: the text 'invoice.txt': 'due' is not a date of"
                                + " the document",
                        "/Dates {year:-1}/Text in an unknown format: the text 'memo.txt': '{issued:short}' names no"
                                + " format of the set",
                        "/Dates {year:-1}/Text with a stray brace: the text 'notice.txt': a brace on line 4 opens or"
                                + " closes no placeholder: 'Pay by {due:long.'",
                        "/Dates {year:-1}/File and text: a version names both a file and a text",
                        "/Dates {year:-1}/Comment of an undeclared date: 'due' is not a date of the document",
                        "/Dates {year:-1}/Misspelt year {yaer:-1}: '{yaer:-1}' is not a placeholder");
    }

    // the search by words for agricultural machinery finds nothing in the scene of the search by meaning
    @Test
    void theTagsOfTheEnglishSetNameNoAgriculturalMachinery() throws IOException {
        assertThat(tagsOf("en").stream().flatMap(List::stream).distinct()).hasSize(7)
                .noneMatch(tag -> tag.toLowerCase().contains("agricultur") || tag.toLowerCase().contains("machinery"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru", "de", "es", "it"})
    void theTagsOfEverySetMirrorTheEnglishOnes(String set) throws IOException {
        List<List<String>> english = tagsOf("en");
        List<List<String>> tags = tagsOf(set);

        assertThat(tags).hasSameSizeAs(english);
        assertThat(tags.stream().map(List::size))
                .containsExactlyElementsOf(english.stream().map(List::size).toList());
        assertThat(tags.stream().flatMap(List::stream).distinct()).hasSize(7);
    }

    @Test
    void aFavoriteOfAnUnknownUserOrOfAnItemInTheTrashIsListed() {
        assertThat(loader.check("favorites-broken"))
                .containsExactlyInAnyOrder(
                        "/Marked: there is no user 'nobody'",
                        "/Marked/Deleted favorite: an item in the trash cannot be a favorite",
                        "/Binned: an item in the trash cannot be a favorite",
                        "/Binned/Inside: an item in the trash cannot be a favorite");
    }

    @Test
    void aSoundDescriptionHasNoProblems() {
        assertThat(loader.check("sample")).isEmpty();
    }

    private static List<List<String>> tagsOf(String set) throws IOException {
        List<List<String>> tags = new ArrayList<>();
        collectTags(DemoSetTemplate.read(set).folders(), tags);

        return tags;
    }

    private static void collectTags(List<DemoSetTemplate.Folder> folders, List<List<String>> tags) {
        for (DemoSetTemplate.Folder folder : folders) {
            folder.documents().forEach(document -> tags.add(document.tags()));
            collectTags(folder.folders(), tags);
        }
    }

    @Test
    void aSetWithoutADescriptionCannotBeRead() {
        assertThat(loader.check("missing"))
                .singleElement()
                .asString()
                .contains("cannot be read");
    }
}

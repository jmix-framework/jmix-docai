package com.company.dms.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
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
                        "/Broken: there is no user 'nobody'",
                        "/Broken: there is no role 'no-such-role'",
                        "/Broken: an access entry must name a user or a role",
                        "/Broken: there is no access level 'OWN'",
                        "/Broken/Twice: two documents have this name",
                        "/Broken/No versions: the document has no versions",
                        "/Broken/Missing file: there is no file 'missing.txt'",
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
                        "/Broken/Tag in another case: the tag 'LEASE' is written 'Lease' elsewhere",
                        "/Broken/Empty tag: a tag has no name",
                        "/Broken/Long tag: the tag '%s' is longer than 50 characters".formatted("x".repeat(51)),
                        "/Broken: two folders have this name",
                        "/: a folder has no name");
    }

    // the search by words for agricultural machinery finds nothing in the scene of the search by meaning
    @Test
    void theTagsOfTheDemoSetsNameNoAgriculturalMachineryAndTheRussianOnesMirrorTheEnglishOnes() throws IOException {
        List<List<String>> english = tagsOf("en");
        List<List<String>> russian = tagsOf("ru");

        assertThat(english.stream().flatMap(List::stream).distinct()).hasSize(7)
                .noneMatch(tag -> tag.toLowerCase().contains("agricultur") || tag.toLowerCase().contains("machinery"));
        assertThat(russian).hasSameSizeAs(english);
        assertThat(russian.stream().map(List::size))
                .containsExactlyElementsOf(english.stream().map(List::size).toList());
        assertThat(russian.stream().flatMap(List::stream).distinct()).hasSize(7);
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

    // the tags of every document of the set, in the order of the description
    private static List<List<String>> tagsOf(String set) throws IOException {
        try (InputStream content = new ClassPathResource("com/company/dms/demo/%s/demo-data.json".formatted(set))
                .getInputStream()) {
            List<List<String>> tags = new ArrayList<>();
            collectTags(JsonMapper.shared().readValue(content, DemoSet.class).folders(), tags);
            return tags;
        }
    }

    private static void collectTags(List<DemoSet.Folder> folders, List<List<String>> tags) {
        for (DemoSet.Folder folder : folders) {
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

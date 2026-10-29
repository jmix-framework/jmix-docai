package com.company.dms.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
                        "/Broken: two folders have this name",
                        "/: a folder has no name");
    }

    @Test
    void aSoundDescriptionHasNoProblems() {
        assertThat(loader.check("sample")).isEmpty();
    }

    @Test
    void aSetWithoutADescriptionCannotBeRead() {
        assertThat(loader.check("missing"))
                .singleElement()
                .asString()
                .contains("cannot be read");
    }
}

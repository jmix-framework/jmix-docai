package com.company.dms.demo;

import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DemoDataStartTest {

    @Autowired
    private DemoDataLoader loader;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Value("${dms.demo-data}")
    private String chosenSet;

    @Test
    void theTestsChooseNoSet() {
        assertThat(chosenSet).isEqualTo(DemoDataLoader.NONE);
        assertThat(loader.loadIfEmpty(chosenSet)).isEqualTo(DemoDataLoader.Outcome.OFF);
    }

    @Test
    void aSetThatDoesNotExistLoadsNothing() {
        assertThat(loader.loadIfEmpty("missing")).isEqualTo(DemoDataLoader.Outcome.INVALID);
    }

    @Test
    void aDatabaseWithFoldersGetsNoSet() {
        Folder draft = dataManager.create(Folder.class);
        draft.setName("Demo start " + UUID.randomUUID());
        draft.setParent(folderService.getRoot());
        Folder folder = folderService.save(draft);

        try {
            assertThat(loader.loadIfEmpty("sample")).isEqualTo(DemoDataLoader.Outcome.NOT_EMPTY);
            assertThat(dataManager.load(Folder.class)
                    .query("e.name = :name")
                    .parameter("name", "Sample contracts")
                    .list()).isEmpty();
        } finally {
            folderService.delete(folder);
        }
    }
}

package com.company.docai.view.folder;

import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class FolderFilterTest {

    @Autowired
    private FolderFilter folderFilter;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "folder-filter-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void filteringByNameKeepsTheMatchesWithTheirParents() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder signed = folder(contracts, "Signed 2026");
        Folder invoices = folder(sandbox, "Invoices");

        List<Folder> shown = folderFilter.filterByName(List.of(sandbox, contracts, signed, invoices), "SIGNED");

        assertThat(shown).containsExactly(sandbox, contracts, signed);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);

        return folderService.save(folder);
    }
}

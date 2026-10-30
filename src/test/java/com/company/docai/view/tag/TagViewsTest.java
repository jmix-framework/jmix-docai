package com.company.docai.view.tag;

import com.company.docai.DocAiApplication;
import com.company.docai.app.listener.entity.TagEventListener;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestTags;
import com.company.docai.test_support.TestTrash;
import com.vaadin.flow.component.button.Button;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vocabulary of tags as the administrator keeps it: the tags by name, a new tag and a new name in a dialog,
 * and the refusals of a taken name and of a tag in use.
 */
@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class TagViewsTest {

    private static final String ADMIN = "admin";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = unconstrainedDataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("tag-views-test-" + suffix);
            return folderService.save(folder);
        });
    }

    // the tags of the sandbox go only after its documents are deleted forever
    @AfterEach
    void deleteSandboxAndTags() {
        systemAuthenticator.runWithUser(ADMIN, () -> {
            folderService.delete(sandbox);
            trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), sandbox.getId())));
        });
        unconstrainedDataManager.load(Tag.class)
                .query("select t from Tag t where t.name like :suffix")
                .parameter("suffix", "%" + suffix)
                .list()
                .forEach(unconstrainedDataManager::remove);
    }

    @Test
    void theListShowsTheTagsByNameWhateverTheCase() {
        TestTags.tag(unconstrainedDataManager, "Supply " + suffix);
        TestTags.tag(unconstrainedDataManager, "office " + suffix);
        TestTags.tag(unconstrainedDataManager, "Lease " + suffix);

        DataGridItems<Tag> items = grid(openList()).getItems();

        assertThat(items).isNotNull();
        assertThat(items.getItems()).extracting(Tag::getName)
                .filteredOn(name -> name.endsWith(suffix))
                .containsExactly("Lease " + suffix, "office " + suffix, "Supply " + suffix);
    }

    @Test
    void aNewTagIsMadeInADialog() {
        TagListView list = openList();

        UiTestUtils.<JmixButton>getComponent(list, "createButton").click();
        TagDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        UiTestUtils.<TypedTextField<String>>getComponent(dialog, "nameField").setValue(" Warehouse " + suffix + " ");
        UiTestUtils.<JmixButton>getComponent(dialog, "saveAndCloseButton").click();

        assertThat(names()).containsExactly("Warehouse " + suffix);
        DataGridItems<Tag> items = grid(list).getItems();
        assertThat(items).isNotNull();
        assertThat(items.getItems()).extracting(Tag::getName).contains("Warehouse " + suffix);
    }

    @Test
    void aNameTakenWhateverTheCaseIsRefused() {
        TestTags.tag(unconstrainedDataManager, "Lease " + suffix);
        Tag office = TestTags.tag(unconstrainedDataManager, "Office " + suffix);
        TagListView list = openList();
        DataGrid<Tag> grid = grid(list);

        grid.select(office);
        UiTestUtils.<JmixButton>getComponent(list, "editButton").click();
        TagDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        UiTestUtils.<TypedTextField<String>>getComponent(dialog, "nameField").setValue("LEASE " + suffix);

        assertThatThrownBy(() -> UiTestUtils.<JmixButton>getComponent(dialog, "saveAndCloseButton").click())
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(TagEventListener.class, "tagNameTaken", "Lease " + suffix));
        assertThat(names()).containsExactly("Lease " + suffix, "Office " + suffix);
    }

    @Test
    void aTagInUseIsNotDeletedAndAnUnusedOneIs() {
        Tag lease = TestTags.tag(unconstrainedDataManager, "Lease " + suffix);
        Tag office = TestTags.tag(unconstrainedDataManager, "Office " + suffix);
        TestTags.tagged(unconstrainedDataManager, upload(), lease);
        TagListView list = openList();
        DataGrid<Tag> grid = grid(list);

        grid.select(lease);
        UiTestUtils.<JmixButton>getComponent(list, "removeButton").click();

        assertThatThrownBy(() -> confirmButton().click())
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(TagService.class, "tagInUse", lease.getName()));

        grid.select(office);
        UiTestUtils.<JmixButton>getComponent(list, "removeButton").click();
        confirmButton().click();

        assertThat(names()).containsExactly("Lease " + suffix);
        assertThat(unconstrainedDataManager.load(Id.of(lease)).optional()).isPresent();
    }

    private TagListView openList() {
        viewNavigators.view(UiTestUtils.getCurrentView(), TagListView.class).navigate();

        return UiTestUtils.getCurrentView();
    }

    private static DataGrid<Tag> grid(TagListView list) {
        return UiTestUtils.getComponent(list, "tagsDataGrid");
    }

    private static Button confirmButton() {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();

        return dialog.getButtons()
                .stream()
                .filter(button -> button.getId().equals(Optional.of(DialogAction.Type.YES.getId())))
                .findFirst()
                .orElseThrow();
    }

    private List<String> names() {
        return unconstrainedDataManager.load(Tag.class)
                .query("select t from Tag t where t.name like :suffix order by t.name")
                .parameter("suffix", "%" + suffix)
                .list()
                .stream()
                .map(Tag::getName)
                .toList();
    }

    private Document upload() {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "a.txt", "text")))
                        .created()
                        .getFirst());
    }
}

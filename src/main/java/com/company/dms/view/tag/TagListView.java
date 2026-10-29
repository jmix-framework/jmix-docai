package com.company.dms.view.tag;

import com.company.dms.app.service.tag.TagService;
import com.company.dms.model.tag.Tag;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;

/**
 * The vocabulary of tags, which the administrator keeps: a tag gets its name in a dialog, and a tag in use is not
 * deleted. Employees add tags from the document card.
 */
@Route(value = "tags", layout = MainView.class)
@ViewController(id = "Tag.list")
@ViewDescriptor(path = "tag-list-view.xml")
public class TagListView extends StandardListView<Tag> {

    @Autowired
    private TagService tagService;

    // a tag goes through the tag service, which keeps a tag that a document carries
    @Install(to = "tagsDataGrid.removeAction", subject = "delegate")
    private void tagsDataGridRemoveActionDelegate(final Collection<Tag> tags) {
        tags.forEach(tagService::delete);
    }
}

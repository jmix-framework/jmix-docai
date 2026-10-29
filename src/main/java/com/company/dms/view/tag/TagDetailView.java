package com.company.dms.view.tag;

import com.company.dms.model.tag.Tag;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

/**
 * The name of a tag. A new name changes the tag on all its documents at once.
 */
@Route(value = "tags/:id", layout = MainView.class)
@ViewController(id = "Tag.detail")
@ViewDescriptor(path = "tag-detail-view.xml")
@EditedEntityContainer("tagDc")
@DialogMode(width = "28em")
public class TagDetailView extends StandardDetailView<Tag> {
}

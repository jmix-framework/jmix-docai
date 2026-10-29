package com.company.dms.app.listener.entity;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.tag.TagService;
import com.company.dms.model.tag.Tag;
import io.jmix.core.Messages;
import io.jmix.core.event.EntitySavingEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the vocabulary of tags whole on every save path: a name loses the spaces around it, is not empty, fits
 * {@link Tag#MAX_NAME_LENGTH} and differs from the names of the other tags whatever the case.
 */
@Component
public class TagEventListener {

    private final TagService tagService;
    private final Messages messages;

    public TagEventListener(TagService tagService, Messages messages) {
        this.tagService = tagService;
        this.messages = messages;
    }

    @EventListener
    public void onTagSaving(EntitySavingEvent<Tag> event) {
        Tag tag = event.getEntity();
        tag.setName(tagService.checkName(tag.getName()));

        tagService.find(tag.getName())
                .filter(namesake -> !namesake.equals(tag))
                .ifPresent(namesake -> {
                    throw new OperationRejectedException(messages.formatMessage(getClass(), "tagNameTaken",
                            namesake.getName()));
                });
    }
}

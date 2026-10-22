package com.company.dms.view.document;

import com.company.dms.model.document.DocumentRevision;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.streams.DownloadEvent;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.flowui.component.image.JmixImage;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Shows one revision of a document in the browser: images, PDF and text from an allow-list,
 * and a download button for anything else.
 */
@FragmentDescriptor("document-preview-fragment.xml")
public class DocumentPreviewFragment extends Fragment<VerticalLayout> {

    private static final int TEXT_LIMIT = 256 * 1024;

    @ViewComponent
    private VerticalLayout previewEmpty;
    @ViewComponent
    private Span previewMessage;
    @ViewComponent
    private JmixImage<Object> previewImage;
    @ViewComponent
    private IFrame previewFrame;
    @ViewComponent
    private Span truncatedNote;
    @ViewComponent
    private Pre previewText;
    @ViewComponent
    private JmixButton downloadButton;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private FileStorageLocator fileStorageLocator;
    @Autowired
    private RevisionDownloader revisionDownloader;
    @Nullable
    private DocumentRevision revision;

    /**
     * Shows the revision, or a hint when there is no revision or its file is gone from the storage.
     */
    public void showRevision(@Nullable DocumentRevision revision) {
        this.revision = revision;

        previewImage.setVisible(false);
        previewFrame.setVisible(false);
        previewText.setVisible(false);
        truncatedNote.setVisible(false);
        downloadButton.setVisible(false);

        if (revision == null) {
            showMessage(messageBundle.getMessage("noDocument"));
            return;
        }

        FileRef file = revision.getFile();
        FileStorage storage = fileStorageLocator.getByName(file.getStorageName());
        if (!storage.fileExists(file)) {
            showMessage(messageBundle.formatMessage("fileMissing", revision.getNumber()));
            return;
        }

        previewEmpty.setVisible(false);
        switch (PreviewType.of(revision.getContentType())) {
            case IMAGE -> {
                previewImage.setSrc(inlineHandler(revision));
                previewImage.setVisible(true);
            }
            case PDF -> {
                previewFrame.setSrc(inlineHandler(revision));
                previewFrame.setVisible(true);
            }
            case TEXT -> showText(storage, revision);
            case NONE -> {
                showMessage(messageBundle.getMessage("noPreview"));
                downloadButton.setVisible(true);
            }
        }
    }

    @Subscribe("downloadButton")
    public void onDownloadButtonClick(final ClickEvent<JmixButton> event) {
        if (revision != null) {
            revisionDownloader.download(revision);
        }
    }

    private void showMessage(String message) {
        previewMessage.setText(message);
        previewEmpty.setVisible(true);
    }

    private void showText(FileStorage storage, DocumentRevision revision) {
        try (InputStream content = storage.openStream(revision.getFile())) {
            previewText.setText(new String(content.readNBytes(TEXT_LIMIT), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        previewText.setVisible(true);

        if (revision.getFileSize() > TEXT_LIMIT) {
            truncatedNote.setText(messageBundle.formatMessage("textTruncated", TEXT_LIMIT / 1024));
            truncatedNote.setVisible(true);
        }
    }

    /**
     * The browser fetches the preview by itself, also while a dialog makes the panel inert: a double click
     * on a row opens the card before the fetch arrives. Vaadin answers such a fetch with 403 unless the
     * handler allows it.
     */
    private DownloadHandler inlineHandler(DocumentRevision revision) {
        FileRef file = revision.getFile();
        DownloadHandler handler = DownloadHandler.fromInputStream(event -> new DownloadResponse(
                        fileStorageLocator.getByName(file.getStorageName()).openStream(file),
                        file.getFileName(), revision.getContentType(), revision.getFileSize()),
                file.getFileName()).inline();

        return new DownloadHandler() {
            @Override
            public void handleDownloadRequest(DownloadEvent event) throws IOException {
                handler.handleDownloadRequest(event);
            }

            @Override
            public String getUrlPostfix() {
                return handler.getUrlPostfix();
            }

            @Override
            public boolean isAllowInert() {
                return true;
            }
        };
    }
}

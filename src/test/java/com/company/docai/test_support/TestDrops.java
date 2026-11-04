package com.company.docai.test_support;

import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.internal.nodefeature.VirtualChildrenList;
import com.vaadin.flow.server.AbstractStreamResource;
import com.vaadin.flow.server.StreamResourceRegistry;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.streams.UploadEvent;
import com.vaadin.flow.server.streams.UploadHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Plays the browser's side of files dropped on an upload drop zone: the requests that send the files to the manager
 * of the zone, and the events of the manager that the browser fires. Both go to the connector of the manager, the
 * element the browser talks to, which the manager keeps as a virtual child of its owner; the connector is looked up
 * from the zone upwards, so the helper does not depend on which component owns the manager.
 */
public final class TestDrops {

    private static final String CONNECTOR = "vaadin-upload-manager-connector";
    private static final String CONTENT_TYPE = "application/octet-stream";
    // the upload handler of the zone puts each file into a temporary directory of its own
    private static final String TEMPORARY_DIRECTORY = "temp_dir";

    private TestDrops() {
    }

    /**
     * Sends a dropped file to the zone as the browser does, then runs the session access in which the upload handler
     * hands the file over: the session of a UI test runs no access by itself.
     */
    public static void receive(UploadDropZone zone, String fileName, String text) {
        Element connector = connectorOf(zone);
        VaadinSession session = VaadinSession.getCurrent();
        VaadinServletService service = (VaadinServletService) session.getService();
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setContent(content);
        VaadinServletRequest request = new VaadinServletRequest(httpRequest, service);

        try {
            uploadHandlerOf(connector).handleUploadRequest(new UploadEvent(request, null, session, fileName,
                    content.length, CONTENT_TYPE, connector, null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        service.runPendingAccessTasks(session);
    }

    /**
     * Fires the event of the browser that refused a dropped file for the reason.
     */
    public static void reject(UploadDropZone zone, String fileName, UploadManager.FileRejectionReason reason) {
        String errorCode = switch (reason) {
            case TOO_MANY_FILES -> "tooManyFiles";
            case FILE_TOO_LARGE -> "fileIsTooBig";
            case INCORRECT_FILE_TYPE -> "incorrectFileType";
            case UNKNOWN -> "unknown";
        };

        ObjectNode data = JsonNodeFactory.instance.objectNode()
                .put("event.detail.fileName", fileName)
                .put("event.detail.errorMessage", errorCode);

        fire(connectorOf(zone), "file-reject", data);
    }

    /**
     * Fires the event of the browser that follows the last file of a drop.
     */
    public static void finishAll(UploadDropZone zone) {
        fire(connectorOf(zone), "all-finished", JsonNodeFactory.instance.objectNode());
    }

    /**
     * Returns the temporary files that hold the received files of that name.
     */
    public static List<Path> temporaryFiles(String fileName) {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"));

        try (Stream<Path> directories = Files.list(temporary)) {
            return directories
                    .filter(directory -> directory.getFileName().toString().startsWith(TEMPORARY_DIRECTORY))
                    .filter(Files::isDirectory)
                    .flatMap(TestDrops::filesIn)
                    .filter(file -> file.getFileName().toString().startsWith(fileName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<Path> filesIn(Path directory) {
        try {
            return Files.list(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Element connectorOf(UploadDropZone zone) {
        for (Element element = zone.getElement(); element != null; element = element.getParent()) {
            // no public API lists the virtual children of an element, so they come from Flow's node feature
            Optional<Element> connector = element.getNode()
                    .getFeatureIfInitialized(VirtualChildrenList.class)
                    .stream()
                    .flatMap(children -> IntStream.range(0, children.size()).mapToObj(children::get))
                    .map(Element::get)
                    .filter(child -> child.getTag().equals(CONNECTOR))
                    .findFirst();

            if (connector.isPresent()) {
                return connector.get();
            }
        }

        throw new IllegalStateException("The zone has no upload manager");
    }

    private static UploadHandler uploadHandlerOf(Element connector) {
        URI target = URI.create(connector.getAttribute("target"));

        AbstractStreamResource resource = VaadinSession.getCurrent()
                .getResourceRegistry()
                .getResource(target)
                .orElseThrow();

        return (UploadHandler) ((StreamResourceRegistry.ElementStreamResource) resource).getElementRequestHandler();
    }

    private static void fire(Element connector, String type, ObjectNode data) {
        // no public API fires a browser event in a server-side test, so the event goes through Flow's listener map
        connector.getNode()
                .getFeature(ElementListenerMap.class)
                .fireEvent(new DomEvent(connector, type, data));
    }
}

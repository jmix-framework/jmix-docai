package com.company.docai.test_support;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.server.AbstractStreamResource;
import com.vaadin.flow.server.StreamResourceRegistry;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.streams.UploadEvent;
import com.vaadin.flow.server.streams.UploadHandler;
import jakarta.servlet.ServletInputStream;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Plays the browser's side of files dropped on an upload drop zone: the requests that send the files to the manager
 * of the zone, and the events of the manager that the browser fires. Both go to the connector of the manager, the
 * element the browser talks to; the connector is taken from the manager of the zone, so the helper works for every
 * zone linked to the manager, wherever the component that owns the manager is.
 */
public final class TestDrops {

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
        receive(zone, fileName, text, () -> {
        });
    }

    /**
     * Sends a dropped file whose transfer takes a while: the action comes after the server has handled the start of
     * the transfer and before the file has arrived, as a click of the user may.
     */
    public static void receive(UploadDropZone zone, String fileName, String text, Runnable whileArriving) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);

        InputStream body = new FilterInputStream(new ByteArrayInputStream(content)) {

            private boolean started;

            @Override
            public int read() throws IOException {
                beforeTheBody();

                return super.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                beforeTheBody();

                return super.read(buffer, offset, length);
            }

            // the server handles the start of a transfer in a session access of its own, before the body arrives
            private void beforeTheBody() {
                if (!started) {
                    started = true;
                    VaadinSession session = VaadinSession.getCurrent();
                    session.getService().runPendingAccessTasks(session);
                    whileArriving.run();
                }
            }
        };

        try {
            send(zone, fileName, content.length, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sends a dropped file whose transfer breaks off, as when the connection drops, then runs the session access in
     * which the upload handler reports it.
     */
    public static void breakOff(UploadDropZone zone, String fileName) {
        InputStream body = new InputStream() {

            @Override
            public int read() throws IOException {
                throw new IOException("The connection was reset");
            }
        };

        try {
            send(zone, fileName, 1024, body);
        } catch (IOException e) {
            // the upload handler passes the broken transfer on once it has reported it
            return;
        }

        throw new IllegalStateException("The transfer of " + fileName + " did not break off");
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
     * Tells whether the server takes a transfer to the manager of the zone now, as it checks before each one: the
     * connector of the manager is attached, enabled and not inert. The server knows the inert state from its last
     * response, so a response is made first.
     */
    public static boolean takesTransfers(UploadDropZone zone) {
        UI.getCurrent()
                .getInternals()
                .getStateTree()
                .collectChanges(change -> {
                });

        StateNode node = connectorOf(zone).getNode();

        return node.isAttached() && node.isEnabled() && !node.isInert();
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

    private static void send(UploadDropZone zone, String fileName, long length, InputStream body) throws IOException {
        Element connector = connectorOf(zone);
        VaadinSession session = VaadinSession.getCurrent();
        VaadinServletService service = (VaadinServletService) session.getService();

        MockHttpServletRequest httpRequest = new MockHttpServletRequest() {

            @Override
            public ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(body);
            }
        };

        VaadinServletRequest request = new VaadinServletRequest(httpRequest, service);
        UploadHandler handler = uploadHandlerOf(connector);

        Component connectorComponent = connector.getComponent()
                .orElseThrow();

        // the server tells the handler the UI of the request, which a file that arrives after its page has gone needs
        ComponentUtil.setData(connectorComponent, "uiid", String.valueOf(UI.getCurrent().getUIId()));

        try {
            handler.handleUploadRequest(new UploadEvent(request, null, session, fileName, length, CONTENT_TYPE,
                    connector, null));
        } finally {
            service.runPendingAccessTasks(session);
        }
    }

    private static Element connectorOf(UploadDropZone zone) {
        UploadManager manager = Objects.requireNonNull(zone.getUploadManager(), "The zone has no upload manager");

        // no public API gives the element of a manager, so it comes from the package-private getter of the manager
        try {
            Method connector = UploadManager.class.getDeclaredMethod("getConnector");
            connector.setAccessible(true);

            return ((Component) connector.invoke(manager)).getElement();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("UploadManager.getConnector() is not in this Vaadin version", e);
        }
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

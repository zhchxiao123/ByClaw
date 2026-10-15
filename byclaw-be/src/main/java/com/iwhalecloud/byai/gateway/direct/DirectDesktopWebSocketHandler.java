package com.iwhalecloud.byai.gateway.direct;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * The desktop viewer's stream (websockify) between the browser and the owner's runtime. The
 * handshake is authenticated by the viewer ticket in the path; the target is always the ticket
 * owner's own runtime. A connected viewer keeps the runtime from being paused.
 */
@Slf4j
public class DirectDesktopWebSocketHandler extends AbstractWebSocketHandler implements HandshakeInterceptor {

    private static final String OWNER = "direct.desktop.owner";
    private static final String UPSTREAM = "direct.desktop.upstream";

    private final DirectDesktop desktop;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30)).readTimeout(Duration.ZERO).pingInterval(Duration.ofSeconds(30)).build();

    public DirectDesktopWebSocketHandler(DirectDesktop desktop) {
        this.desktop = desktop;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String path = request.getURI().getRawPath();
        int at = path.indexOf(DirectDesktopController.PREFIX + "/");
        String[] split = at < 0 ? new String[0]
            : path.substring(at + DirectDesktopController.PREFIX.length() + 1).split("/", 2);
        Optional<RuntimeOwnerScope> owner = split.length == 0 ? Optional.empty() : desktop.verify(split[0]);
        if (owner.isEmpty()) {
            response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(OWNER, owner.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession browser) {
        RuntimeOwnerScope owner = (RuntimeOwnerScope) browser.getAttributes().get(OWNER);
        Optional<ReadyRuntime> runtime = desktop.runtime(owner, false);
        URI uri = browser.getUri();
        if (runtime.isEmpty() || uri == null) {
            close(browser, CloseStatus.SERVICE_RESTARTED.withReason("runtime not running"));
            return;
        }
        // Past /direct-desktop/<ticket>: the runtime's own path (websockify).
        String afterPrefix = uri.getRawPath().substring(uri.getRawPath().indexOf(DirectDesktopController.PREFIX)
            + DirectDesktopController.PREFIX.length() + 1);
        String path = afterPrefix.contains("/") ? afterPrefix.substring(afterPrefix.indexOf('/')) : "/";
        Request upstream = desktop.request(runtime.get(), path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()))
            .build();
        desktop.opened(owner.key());
        browser.getAttributes().put(UPSTREAM, client.newWebSocket(upstream, new Relay(browser)));
        log.info("Direct desktop viewer connected, owner={}", owner.key());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession browser, BinaryMessage message) {
        WebSocket upstream = (WebSocket) browser.getAttributes().get(UPSTREAM);
        if (upstream != null) upstream.send(ByteString.of(message.getPayload()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession browser, TextMessage message) {
        WebSocket upstream = (WebSocket) browser.getAttributes().get(UPSTREAM);
        if (upstream != null) upstream.send(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession browser, CloseStatus status) {
        WebSocket upstream = (WebSocket) browser.getAttributes().remove(UPSTREAM);
        RuntimeOwnerScope owner = (RuntimeOwnerScope) browser.getAttributes().get(OWNER);
        if (upstream != null) {
            upstream.close(1000, null);
            if (owner != null) desktop.closed(owner.key());
            log.info("Direct desktop viewer closed, owner={}", owner == null ? null : owner.key());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession browser, Throwable exception) {
        close(browser, CloseStatus.SERVER_ERROR);
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        }
        catch (java.io.IOException ignored) {
            // already gone
        }
    }

    /** Runtime to browser; either side closing closes the other. */
    private static final class Relay extends WebSocketListener {
        private final WebSocketSession browser;

        private Relay(WebSocketSession browser) {
            this.browser = browser;
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            send(new BinaryMessage(bytes.toByteArray()));
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            send(new TextMessage(text));
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            socket.close(1000, null);
            close(browser, CloseStatus.NORMAL);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, okhttp3.Response response) {
            close(browser, CloseStatus.SERVER_ERROR.withReason("runtime stream failed"));
        }

        private void send(org.springframework.web.socket.WebSocketMessage<?> message) {
            synchronized (browser) {
                try {
                    if (browser.isOpen()) browser.sendMessage(message);
                }
                catch (java.io.IOException e) {
                    close(browser, CloseStatus.SERVER_ERROR);
                }
            }
        }
    }
}

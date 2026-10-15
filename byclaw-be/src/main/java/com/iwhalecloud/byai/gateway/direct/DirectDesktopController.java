package com.iwhalecloud.byai.gateway.direct;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.RuntimeUnavailableException;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;

/** HTTP side of the owner's desktop viewer; the WebSocket stream is {@link DirectDesktopWebSocketHandler}. */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectDesktopController {

    static final String PREFIX = "/direct-desktop";

    private final DirectDesktop desktop;

    public DirectDesktopController(DirectDesktop desktop) {
        this.desktop = desktop;
    }

    @RequestMapping(path = {PREFIX, PREFIX + "/**"}, method = {RequestMethod.GET, RequestMethod.HEAD})
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // /direct-desktop/<ticket>/<path>: the ticket authenticates every page, asset and stream request.
        String rest = request.getRequestURI().substring(request.getContextPath().length() + PREFIX.length());
        String[] split = rest.startsWith("/") ? rest.substring(1).split("/", 2) : new String[0];
        Optional<RuntimeOwnerScope> owner = split.length == 0 ? Optional.empty() : desktop.verify(split[0]);
        if (owner.isEmpty()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (split.length == 1) {
            response.sendRedirect(request.getContextPath() + PREFIX + "/" + split[0] + "/"
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
            return;
        }
        String path = "/" + split[1];
        boolean page = "/".equals(path) || "/index.html".equals(path);
        Optional<ReadyRuntime> runtime;
        try {
            runtime = desktop.runtime(owner.get(), page);
        }
        catch (RuntimeUnavailableException e) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage());
            return;
        }
        if (runtime.isEmpty()) {
            response.sendError(HttpServletResponse.SC_CONFLICT, "运行环境未运行，请重新打开桌面");
            return;
        }
        String pathAndQuery = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        okhttp3.Request.Builder upstream = desktop.request(runtime.get(), pathAndQuery);
        upstream = "HEAD".equals(request.getMethod()) ? upstream.head() : upstream.get();
        try (Response answer = desktop.cube().callWithoutRedirects(upstream.build()).execute()) {
            response.setStatus(answer.code());
            // Location is relative to the viewer page (e.g. ?sessionId=shared) and stays under the ticket path.
            for (String header : new String[] {"Content-Type", "Cache-Control", "Last-Modified", "ETag", "Location"}) {
                String value = answer.header(header);
                if (value != null) response.setHeader(header, value);
            }
            if (answer.body() != null && !"HEAD".equals(request.getMethod())) {
                try (InputStream in = answer.body().byteStream(); OutputStream out = response.getOutputStream()) {
                    in.transferTo(out);
                }
            }
        }
    }
}

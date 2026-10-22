package com.secureitsource.ui.config;

import java.io.IOException;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// The server listens on 127.0.0.1, but any web page open in the same browser can still send it requests. Two checks
// keep those pages out:
//  - the Host header must be localhost, so a DNS-rebound name can't reach the API;
//  - every POST must carry REQUEST_HEADER. Browsers only let another site send a custom header after a CORS preflight,
//    which this server never allows, so another site can't start a run.
@Component
@Order(0)
public class LocalOnlyFilter extends OncePerRequestFilter {

    public static final String REQUEST_HEADER = "X-ALMS-Request";

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!LOCAL_HOSTS.contains(hostName(request.getHeader("Host")))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Open the UI as http://localhost.");
            return;
        }

        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod()) && request.getHeader(REQUEST_HEADER) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing " + REQUEST_HEADER + " header.");
            return;
        }

        chain.doFilter(request, response);
    }

    static String hostName(String host) {
        if (host == null) {
            return "";
        }
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.indexOf(':');
        return (colon < 0 ? host : host.substring(0, colon)).toLowerCase();
    }
}

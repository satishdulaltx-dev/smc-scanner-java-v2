package com.smcscanner.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Simple token guard for sensitive POST endpoints.
 *
 * Set SCANNER_DASHBOARD_TOKEN in Railway env vars.
 * Clients pass it as:
 *   - Header:  X-Dashboard-Token: <token>
 *   - OR query: ?token=<token>
 *
 * If SCANNER_DASHBOARD_TOKEN is blank/unset, protection is disabled
 * (safe default for local dev).
 *
 * Protected endpoints (all POST):
 *   /api/alpaca/cancel-all
 *   /api/alpaca/close-equity
 *   /api/test-alert
 *   /api/trades/resolve
 *   /api/adaptive/outcome
 *   /api/adaptive/reset
 *   /api/send-eod-discord
 *   /api/optimize
 */
@Component
public class ApiTokenFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/alpaca/cancel-all",
            "/api/alpaca/close-equity",
            "/api/test-alert",
            "/api/trades/resolve",
            "/api/adaptive/outcome",
            "/api/adaptive/reset",
            "/api/send-eod-discord",
            "/api/optimize"
    );

    private final ScannerConfig config;

    public ApiTokenFilter(ScannerConfig config) {
        this.config = config;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        String configuredToken = config.getDashboardToken();

        // If no token configured, skip protection entirely (local dev / backwards compat)
        if (configuredToken == null || configuredToken.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        String path   = request.getRequestURI();
        String method = request.getMethod();

        // Only guard protected POST endpoints
        if ("POST".equalsIgnoreCase(method) && PROTECTED_PATHS.contains(path)) {
            String headerToken = request.getHeader("X-Dashboard-Token");
            String queryToken  = request.getParameter("token");
            String provided    = headerToken != null ? headerToken : queryToken;

            if (!configuredToken.equals(provided)) {
                log.warn("SECURITY: Unauthorized POST to {} from {} — token mismatch",
                        path, request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Unauthorized — missing or invalid token\"}");
                return;
            }
        }

        chain.doFilter(req, res);
    }
}

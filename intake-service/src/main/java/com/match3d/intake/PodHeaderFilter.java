package com.match3d.intake;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every response with the name of the copy that served it, so a test
 * sending requests through the one Service address can count how many copies
 * took traffic and watch that set shrink to the survivors when one is killed.
 *
 * Kubernetes sets HOSTNAME to the pod's name; off the cluster it is whatever
 * the machine is called, which is enough to tell copies apart there too.
 */
@Component
public class PodHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Intake-Pod";

    private final String pod;

    public PodHeaderFilter() {
        String name = System.getenv("HOSTNAME");
        this.pod = (name == null || name.isBlank()) ? "unknown" : name;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, pod);
        chain.doFilter(request, response);
    }
}

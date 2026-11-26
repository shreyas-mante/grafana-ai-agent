package com.pulseagent.sdk;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** Auto-configures exception reporting for Spring MVC apps when pulseagent.enabled=true. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(HandlerExceptionResolver.class)
@ConditionalOnProperty(prefix = "pulseagent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PulseAgentProperties.class)
public class PulseAgentAutoConfiguration {

    @Bean
    ErrorReporter pulseAgentErrorReporter(PulseAgentProperties props, ObjectProvider<GitProperties> git) {
        if (props.getCommitSha() == null || props.getCommitSha().isBlank()) {
            GitProperties g = git.getIfAvailable();
            if (g != null && g.getCommitId() != null) props.setCommitSha(g.getCommitId());
        }
        return new ErrorReporter(props);
    }

    /** Runs first, reports the exception, then returns null so normal error handling continues. */
    @Bean
    HandlerExceptionResolver pulseAgentExceptionResolver(ErrorReporter reporter) {
        return new ReportingResolver(reporter);
    }

    static class ReportingResolver implements HandlerExceptionResolver, Ordered {
        private final ErrorReporter reporter;

        ReportingResolver(ErrorReporter reporter) {
            this.reporter = reporter;
        }

        @Override
        public ModelAndView resolveException(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
            reporter.report(ex, req.getMethod(), req.getRequestURI());
            return null;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}

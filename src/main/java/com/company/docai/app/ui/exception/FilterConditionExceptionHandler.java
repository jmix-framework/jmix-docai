package com.company.docai.app.ui.exception;

import io.jmix.core.JmixOrder;
import io.jmix.core.Messages;
import io.jmix.data.impl.jpql.JpqlSyntaxException;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.filter.SingleFilterComponentBase;
import io.jmix.flowui.component.genericfilter.GenericFilter;
import io.jmix.flowui.component.logicalfilter.GroupFilter;
import io.jmix.flowui.exception.UiExceptionHandler;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.persistence.exceptions.JPQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Explains a filter condition that cannot run, which Jmix would show in the technical error dialog and log with its
 * stack trace. Only the errors of the query that a filter starts are taken: a failing query of the application, or a
 * defect of other code that runs under the filter's load, stays an error.
 */
@Component
@Order(JmixOrder.HIGHEST_PRECEDENCE + 40)
public class FilterConditionExceptionHandler implements UiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FilterConditionExceptionHandler.class);

    private static final Set<String> LOADING_FILTERS = Set.of(GenericFilter.class.getName(),
            SingleFilterComponentBase.class.getName(), GroupFilter.class.getName());

    private static final List<String> PERSISTENCE_PACKAGES = List.of("org.eclipse.persistence.", "io.jmix.eclipselink.",
            "io.jmix.data.");

    private static final String SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION = "42";
    private static final String DATA_EXCEPTION = "22";

    private static final Pattern BREAKS = Pattern.compile("[\\p{Z}\\p{Cc}\\p{Cf}]+");
    private static final int MAX_REASON_LENGTH = 300;

    private final Notifications notifications;
    private final Messages messages;

    public FilterConditionExceptionHandler(Notifications notifications, Messages messages) {
        this.notifications = notifications;
        this.messages = messages;
    }

    @Override
    public boolean handle(Throwable exception) {
        List<Throwable> chain = ExceptionUtils.getThrowableList(exception);
        Optional<String> key = startedByFilter(chain) ? keyOf(chain) : Optional.empty();

        if (key.isEmpty()) {
            return false;
        }

        log.info("A filter condition is not applied: {}", reasonOf(chain));

        notifications.create(messages.getMessage(getClass(), key.get()))
                .withType(Notifications.Type.WARNING)
                .show();

        return true;
    }

    private static boolean startedByFilter(List<Throwable> chain) {
        // the load of a filter runs under its frames, the application's own loads do not
        return chain.stream()
                .flatMap(throwable -> Arrays.stream(throwable.getStackTrace()))
                .map(StackTraceElement::getClassName)
                .anyMatch(LOADING_FILTERS::contains);
    }

    private static Optional<String> keyOf(List<Throwable> chain) {
        boolean refused = chain.stream()
                .anyMatch(FilterConditionExceptionHandler::isRefusal);

        if (refused) {
            return Optional.of("conditionRefused");
        }

        boolean invalid = chain.stream()
                .anyMatch(FilterConditionExceptionHandler::isInvalidQuery);

        return invalid ? Optional.of("conditionInvalid") : Optional.empty();
    }

    private static boolean isInvalidQuery(Throwable throwable) {
        if (throwable instanceof JpqlSyntaxException || throwable instanceof JPQLException) {
            return true;
        }

        // EclipseLink refuses an attribute the entity lacks or a parameter of another type as an illegal argument, and
        // a parameter without a value as an illegal state; thrown by other code, the two are defects
        if (!(throwable instanceof IllegalArgumentException || throwable instanceof IllegalStateException)) {
            return false;
        }

        StackTraceElement[] trace = throwable.getStackTrace();

        return trace.length > 0 && PERSISTENCE_PACKAGES.stream()
                .anyMatch(trace[0].getClassName()::startsWith);
    }

    private static boolean isRefusal(Throwable throwable) {
        if (!(throwable instanceof SQLException sql) || sql.getSQLState() == null) {
            return false;
        }

        return sql.getSQLState().startsWith(SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION)
                || sql.getSQLState().startsWith(DATA_EXCEPTION);
    }

    private static String reasonOf(List<Throwable> chain) {
        Throwable cause = chain.getLast();
        // the message holds the text of the condition, which may break the line of the log or reorder it
        String message = BREAKS.matcher(String.valueOf(cause.getMessage())).replaceAll(" ").strip();

        return StringUtils.abbreviate(cause.getClass().getSimpleName() + ": " + message, MAX_REASON_LENGTH);
    }
}

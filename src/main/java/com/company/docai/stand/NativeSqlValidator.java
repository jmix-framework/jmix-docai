package com.company.docai.stand;

import io.jmix.aitools.dataload.execution.GeneratedJpqlResult;
import io.jmix.aitools.dataload.validation.JpqlResultValidator;
import io.jmix.aitools.dataload.validation.JpqlValidationIssue;
import io.jmix.aitools.dataload.validation.validator.ReadOnlyQueryValidator;
import io.jmix.core.JmixOrder;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Refuses a query of the assistant's query tool that puts SQL of its own into JPQL: SQL(...) and FUNCTION(...) reach
 * the database of the copy as they are written. The add-on refuses them too, but it reads the query in small letters,
 * and a letter whose capital is S or I, as the long s (U+017F) or the dotless i (U+0131), passes that check, while the
 * parsers of Jmix and EclipseLink read the letter as S or I.
 */
@Component
@Profile("stand")
public class NativeSqlValidator implements JpqlResultValidator, Ordered {

    // Jmix's parser takes these two of EclipseLink's escapes and refuses FUNC, OPERATOR, COLUMN and TABLE
    private static final List<String> ESCAPES = List.of("SQL(", "FUNCTION(");

    /**
     * Whether the JPQL holds SQL(...) or FUNCTION(...) as Jmix's parser reads a query: each letter on its own as its
     * capital, nothing between the name and the parenthesis. A string literal counts too, so a rare query that only
     * names them in a literal is refused as well.
     */
    public static boolean namesNativeSql(@Nullable String jpql) {
        if (jpql == null) {
            return false;
        }

        StringBuilder capitals = new StringBuilder(jpql.length());

        for (int i = 0; i < jpql.length(); i++) {
            capitals.append(Character.toUpperCase(jpql.charAt(i)));
        }

        return ESCAPES.stream()
                .anyMatch(escape -> capitals.indexOf(escape) >= 0);
    }

    @Override
    public List<JpqlValidationIssue> validate(GeneratedJpqlResult result) {
        if (!namesNativeSql(result.getJpql())) {
            return List.of();
        }

        return List.of(new JpqlValidationIssue(ReadOnlyQueryValidator.JPQL_NATIVE_FUNCTION_CODE,
                "Native escape functions SQL(...) and FUNCTION(...) are not allowed",
                ReadOnlyQueryValidator.JPQL_NATIVE_FUNCTION_GUIDANCE));
    }

    // next to the add-on's check of read-only queries
    @Override
    public int getOrder() {
        return JmixOrder.HIGHEST_PRECEDENCE + 210;
    }
}

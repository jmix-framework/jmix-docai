package com.company.docai.demo;

import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Computes the dates of a demo set for the day the set loads, today. A set writes a date as an expression:
 * <ul>
 *     <li>{@code today}, or days from it: {@code +23d}, {@code -5d};</li>
 *     <li>the first or the last day of a month, a quarter or a year, whole periods away: {@code month-end},
 *     {@code quarter-end+1}, {@code year-start-1}; after {@code @}, of the period of a named date of the document
 *     instead of today: {@code month-end@issued}, {@code month-start+1@signed};</li>
 *     <li>a named date of the document: {@code issued};</li>
 *     <li>any of them, days away: {@code issued-20d}, {@code year-end-14d}, {@code month-start+1@signed+9d};</li>
 *     <li>a date of ISO 8601, which stays where it is: {@code 2026-10-21}.</li>
 * </ul>
 * A time of the history is a date and the time of day, {@code issued 09:42}, or a date and time of ISO 8601,
 * {@code 2026-10-21T09:15}. A name, a field or a text puts a named date in with a format of the set,
 * {@code {due:long}}, and a year with {@code {year:-1}}, {@code {year:+1}}, {@code {year:issued}} or
 * {@code {year:issued-1}}.
 */
public class DemoCalendar {

    private static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ISO_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?");
    private static final Pattern TIME = Pattern.compile("(?<date>\\S+) (?<time>\\d{2}:\\d{2})");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(?<content>[^{}\\r\\n]*)}");
    private static final Pattern REFERENCE = Pattern.compile("(?<name>[a-z][A-Za-z0-9]*):(?<format>[a-z][A-Za-z0-9]*)");
    private static final Pattern YEAR =
            Pattern.compile("year:(?:(?<years>[+-]\\d+)|(?<name>[a-z][A-Za-z0-9]*)(?<offset>[+-]\\d+)?)");

    // a period of the calendar, a name or today, then days away: each part may be left out, though not all of them
    private static final Pattern DATE = Pattern.compile("(?:(?<period>month|quarter|year)-(?<edge>start|end)"
            + "(?<shift>[+-]\\d+)?(?:@(?<anchor>[a-z][A-Za-z0-9]*))?|(?<name>[a-z][A-Za-z0-9]*))?"
            + "(?:(?<days>[+-]\\d+)d)?");

    // the words of the expressions themselves
    private static final Set<String> TAKEN = Set.of("today", "year");

    private final LocalDate today;
    private final Map<String, DateTimeFormatter> formats;

    /**
     * Creates the calendar of a set for a day.
     *
     * @param today   the day the set loads
     * @param locale  the language of the set, which names the months
     * @param formats the formats of the dates of the set by their names, as patterns of {@link DateTimeFormatter}
     * @throws IllegalArgumentException when a format is not a pattern of a date
     */
    public DemoCalendar(LocalDate today, Locale locale, Map<String, String> formats) {
        this.today = today;

        this.formats = formats.entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        format -> formatOf(format.getKey(), format.getValue(), locale, today)));
    }

    // a pattern may name a field a date lacks, such as the hour, and then fails only when it writes a date
    private static DateTimeFormatter formatOf(String name, @Nullable String pattern, Locale locale, LocalDate today) {
        String problem = "the format '%s' is not a pattern of a date: '%s'".formatted(name, pattern);

        if (pattern == null) {
            throw new IllegalArgumentException(problem);
        }

        try {
            DateTimeFormatter format = DateTimeFormatter.ofPattern(pattern, locale);
            format.format(today);

            return format;
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IllegalArgumentException(problem, e);
        }
    }

    /**
     * Computes the named dates of a document, each after the dates it refers to.
     *
     * @param declared the expressions of the dates by their names
     * @throws IllegalArgumentException when a name is not a word in camelCase or is taken, an expression cannot be
     *                                  read, a date refers to a name the document does not declare, or dates refer
     *                                  to each other
     */
    public Dates datesOf(Map<String, String> declared) {
        for (Map.Entry<String, String> date : declared.entrySet()) {
            if (!NAME.matcher(date.getKey()).matches() || TAKEN.contains(date.getKey())) {
                throw new IllegalArgumentException("'" + date.getKey() + "' cannot name a date");
            }

            if (date.getValue() == null || !isDate(date.getValue().strip())) {
                throw new IllegalArgumentException("the date '%s' is not an expression: '%s'"
                        .formatted(date.getKey(), date.getValue()));
            }
        }

        Map<String, LocalDate> named = new LinkedHashMap<>();
        declared.keySet().forEach(name -> resolve(name, declared, named, new ArrayList<>()));

        return new Dates(named);
    }

    // the path holds the dates being computed, so a reference back to one of them closes a cycle
    private LocalDate resolve(String name, Map<String, String> declared, Map<String, LocalDate> named,
                              List<String> path) {
        LocalDate known = named.get(name);

        if (known != null) {
            return known;
        }

        if (path.contains(name)) {
            List<String> cycle = path.subList(path.indexOf(name), path.size());
            throw new IllegalArgumentException("the dates '" + String.join("', '", cycle) + "' refer to each other");
        }

        path.add(name);

        LocalDate date = evaluate(declared.get(name), reference -> {
            if (!declared.containsKey(reference)) {
                throw new IllegalArgumentException("the date '%s' refers to '%s', which the document does not declare"
                        .formatted(name, reference));
            }

            return resolve(reference, declared, named, path);
        });

        path.removeLast();
        named.put(name, date);

        return date;
    }

    private static boolean isDate(String expression) {
        return ISO_DATE.matcher(expression).matches()
                || !expression.isEmpty() && DATE.matcher(expression).matches();
    }

    private LocalDate evaluate(String expression, Function<String, LocalDate> named) {
        String text = expression.strip();

        if (ISO_DATE.matcher(text).matches()) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("'" + expression + "' is not a date", e);
            }
        }

        Matcher matcher = DATE.matcher(text);

        if (text.isEmpty() || !matcher.matches()) {
            throw new IllegalArgumentException("'" + expression + "' is not a date");
        }

        LocalDate date = today;

        // an offset far enough away leaves the range of LocalDate, or overflows a long before the range is checked
        try {
            if (matcher.group("period") != null) {
                LocalDate anchor = matcher.group("anchor") == null ? today : named.apply(matcher.group("anchor"));
                int shift = matcher.group("shift") == null ? 0 : Integer.parseInt(matcher.group("shift"));
                date = edge(matcher.group("period"), "start".equals(matcher.group("edge")), anchor, shift);
            } else if (matcher.group("name") != null && !"today".equals(matcher.group("name"))) {
                date = named.apply(matcher.group("name"));
            }

            return matcher.group("days") == null ? date : date.plusDays(Long.parseLong(matcher.group("days")));
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException("'" + expression + "' is not a date", e);
        }
    }

    // the last day of a month is its last day in any month: a month away from the 31st of January is February 28 or 29
    private static LocalDate edge(String period, boolean start, LocalDate anchor, int shift) {
        int months = switch (period) {
            case "month" -> 1;
            case "quarter" -> 3;
            default -> 12;
        };

        YearMonth first = YearMonth.of(anchor.getYear(), (anchor.getMonthValue() - 1) / months * months + 1)
                .plusMonths((long) months * shift);

        return start ? first.atDay(1) : first.plusMonths(months - 1L).atEndOfMonth();
    }

    private LocalDateTime time(String expression, Function<String, LocalDate> named) {
        String text = expression.strip();

        try {
            if (ISO_TIME.matcher(text).matches()) {
                return LocalDateTime.parse(text);
            }

            Matcher matcher = TIME.matcher(text);

            if (matcher.matches()) {
                return evaluate(matcher.group("date"), named).atTime(LocalTime.parse(matcher.group("time")));
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + expression + "' is not a time", e);
        }

        throw new IllegalArgumentException("'" + expression + "' is not a time");
    }

    private String text(String template, Function<String, LocalDate> named) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder text = new StringBuilder();

        while (matcher.find()) {
            String value = placeholder(matcher.group(), matcher.group("content"), named);
            matcher.appendReplacement(text, Matcher.quoteReplacement(value));
        }

        matcher.appendTail(text);

        // a placeholder never spans lines, so a brace a line keeps without its placeholders belongs to none
        List<String> lines = template.lines()
                .toList();

        for (int number = 1; number <= lines.size(); number++) {
            String line = lines.get(number - 1).strip();

            String rest = PLACEHOLDER.matcher(line)
                    .replaceAll("");

            if (rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0) {
                throw new IllegalArgumentException("a brace on line %d opens or closes no placeholder: '%s'"
                        .formatted(number, line.length() > 80 ? line.substring(0, 79) + "…" : line));
            }
        }

        return text.toString();
    }

    private String placeholder(String placeholder, String content, Function<String, LocalDate> named) {
        Matcher year = YEAR.matcher(content);

        if (year.matches()) {
            if (year.group("years") != null) {
                return String.valueOf(today.getYear() + Integer.parseInt(year.group("years")));
            }

            int offset = year.group("offset") == null ? 0 : Integer.parseInt(year.group("offset"));

            return String.valueOf(named.apply(year.group("name")).getYear() + offset);
        }

        Matcher reference = REFERENCE.matcher(content);

        if (!reference.matches()) {
            throw new IllegalArgumentException("'" + placeholder + "' is not a placeholder");
        }

        DateTimeFormatter format = formats.get(reference.group("format"));

        if (format == null) {
            throw new IllegalArgumentException("'" + placeholder + "' names no format of the set");
        }

        return format.format(named.apply(reference.group("name")));
    }

    /**
     * The named dates of one document on the day of the calendar, and what the document writes with them.
     */
    public final class Dates {

        private final Map<String, LocalDate> named;

        private Dates(Map<String, LocalDate> named) {
            this.named = Map.copyOf(named);
        }

        /**
         * Returns the date of an expression.
         *
         * @throws IllegalArgumentException when the expression cannot be read or names a date the document does not
         *                                  declare
         */
        public LocalDate date(String expression) {
            return evaluate(expression, this::named);
        }

        /**
         * Returns the time of an expression of the history: a date and {@code HH:mm}, or a date and time of ISO 8601.
         *
         * @throws IllegalArgumentException when the expression cannot be read or names a date the document does not
         *                                  declare
         */
        public LocalDateTime time(String expression) {
            return DemoCalendar.this.time(expression, this::named);
        }

        /**
         * Returns the text with its placeholders replaced: the named dates in the formats of the set, and the years.
         *
         * @throws IllegalArgumentException when a placeholder cannot be read or names a date the document does not
         *                                  declare or a format the set lacks
         */
        public String text(String template) {
            return DemoCalendar.this.text(template, this::named);
        }

        /**
         * Returns the value of a field: a named date as {@code yyyy-MM-dd}, any other value as a text with its
         * placeholders replaced.
         *
         * @throws IllegalArgumentException as {@link #text} does
         */
        public String value(String value) {
            LocalDate date = named.get(value);

            return date != null ? date.toString() : text(value);
        }

        private LocalDate named(String name) {
            LocalDate date = named.get(name);

            if (date == null) {
                throw new IllegalArgumentException("'" + name + "' is not a date of the document");
            }

            return date;
        }
    }
}

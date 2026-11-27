package com.company.docai.demo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The expressions of the dates of a demo set, computed for a day: November 7, 2026, a Saturday in the fourth quarter.
 */
class DemoCalendarTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private static final Map<String, String> ENGLISH = Map.of(
            "long", "MMMM d, yyyy",
            "monthDay", "MMMM d",
            "monthYear", "MMMM yyyy");

    // Polish names a month inside a date otherwise than alone, as Russian does
    private static final Map<String, String> POLISH = Map.of(
            "long", "d MMMM yyyy 'r.'",
            "short", "dd.MM.yyyy",
            "monthYear", "LLLL yyyy 'r.'");

    private final DemoCalendar calendar = new DemoCalendar(TODAY, Locale.ENGLISH, ENGLISH);

    @Test
    void todayAndTheDaysAwayFromItCountFromTheDayOfTheCalendar() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of());

        assertThat(dates.date("today")).isEqualTo(TODAY);
        assertThat(dates.date("+23d")).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(dates.date("-5d")).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void aPeriodGivesItsFirstOrLastDay() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of());

        assertThat(dates.date("month-start")).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(dates.date("month-end")).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(dates.date("quarter-start")).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(dates.date("quarter-end")).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(dates.date("year-start")).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(dates.date("year-end")).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void aPeriodMovesByWholePeriods() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of());

        assertThat(dates.date("quarter-end+1")).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(dates.date("month-end-2")).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(dates.date("month-start+3")).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(dates.date("year-start-1")).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(dates.date("year-end+1")).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void theEndOfAMonthIsItsLastDayInAShortMonthAndInALeapYear() {
        Map<String, String> declared = Map.of("signed", "2027-01-31", "due", "month-end+1@signed");

        assertThat(calendar.datesOf(declared).date("due")).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(new DemoCalendar(LocalDate.of(2028, 1, 31), Locale.ENGLISH, ENGLISH)
                .datesOf(Map.of())
                .date("month-end+1")).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void aDateRefersToTheOtherDatesOfItsDocumentInAnyOrder() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("end", "month-end+11@start");
        declared.put("start", "month-start+1@signed");
        declared.put("firstPayment", "month-start@start+4d");
        declared.put("signed", "-253d");

        DemoCalendar.Dates dates = calendar.datesOf(declared);

        assertThat(dates.date("signed")).isEqualTo(LocalDate.of(2026, 2, 27));
        assertThat(dates.date("start")).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(dates.date("firstPayment")).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(dates.date("end")).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(dates.date("signed+3d")).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void aPeriodTakesDaysAwayToo() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of("signed", "-193d"));

        assertThat(dates.date("year-end-14d")).isEqualTo(LocalDate.of(2026, 12, 17));
        assertThat(dates.date("month-start+1@signed+11d")).isEqualTo(LocalDate.of(2026, 5, 12));
        assertThat(dates.date("year-start-1+225d")).isEqualTo(LocalDate.of(2025, 8, 14));
    }

    @Test
    void anIsoDateStaysWhereItIsOnAnyDay() {
        DemoCalendar later = new DemoCalendar(LocalDate.of(2028, 6, 1), Locale.ENGLISH, ENGLISH);

        assertThat(calendar.datesOf(Map.of("law", "2006-07-27")).date("law")).isEqualTo(LocalDate.of(2006, 7, 27));
        assertThat(later.datesOf(Map.of("law", "2006-07-27")).date("law")).isEqualTo(LocalDate.of(2006, 7, 27));
    }

    @Test
    void aTimeIsADateAndATimeOfDay() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of("issued", "-1d"));

        assertThat(dates.time("issued 09:42")).isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 42));
        assertThat(dates.time("-12d 16:05")).isEqualTo(LocalDateTime.of(2026, 10, 26, 16, 5));
        assertThat(dates.time("2026-10-21T09:15")).isEqualTo(LocalDateTime.of(2026, 10, 21, 9, 15));
    }

    @Test
    void aTextWritesTheNamedDatesInTheFormatsOfTheSet() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of("issued", "-5d", "start", "month-start@issued"));

        assertThat(dates.text("dated {issued:long}, from {start:monthDay}, for {issued:monthYear}"))
                .isEqualTo("dated November 2, 2026, from November 1, for November 2026");
    }

    @Test
    void aTextWritesTheMonthsInTheLanguageOfTheSetAndInTheFormOfTheFormat() {
        DemoCalendar.Dates dates = new DemoCalendar(TODAY, Locale.forLanguageTag("pl"), POLISH)
                .datesOf(Map.of("completed", "-7d"));

        assertThat(dates.text("{completed:long} | {completed:short} | {completed:monthYear}"))
                .isEqualTo("31 października 2026 r. | 31.10.2026 | październik 2026 r.");
    }

    @Test
    void aTextWritesYearsFromTodayOrFromANamedDate() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of("signed", "year-start-1+69d"));

        assertThat(dates.text("{year:-1} {year:+1} {year:+0} {year:signed} {year:signed-1}"))
                .isEqualTo("2025 2027 2026 2025 2024");
    }

    @Test
    void aFieldThatHoldsANamedDateGetsItAndAnotherFieldGetsItsText() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of("due", "month-end", "signed", "-67d"));

        assertThat(dates.value("due")).isEqualTo("2026-11-30");
        assertThat(dates.value("15/{year:signed}")).isEqualTo("15/2026");
        assertThat(dates.value("lease")).isEqualTo("lease");
    }

    @Test
    void aDateThatRefersToANameTheDocumentDoesNotDeclareIsAMistake() {
        assertThatThrownBy(() -> calendar.datesOf(Map.of("start", "month-start@signd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the date 'start' refers to 'signd', which the document does not declare");
        assertThatThrownBy(() -> calendar.datesOf(Map.of())
                .date("issued"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'issued' is not a date of the document");
    }

    @Test
    void datesThatReferToEachOtherAreAMistake() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("start", "end-30d");
        declared.put("end", "start+30d");

        assertThatThrownBy(() -> calendar.datesOf(declared))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the dates 'start', 'end' refer to each other");
    }

    @Test
    void anExpressionThatCannotBeReadIsAMistake() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of());

        assertThatThrownBy(() -> calendar.datesOf(Map.of("due", "next-week")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the date 'due' is not an expression: 'next-week'");
        assertThatThrownBy(() -> dates.date("2026-02-30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'2026-02-30' is not a date");
        assertThatThrownBy(() -> dates.time("-1d 9:42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'-1d 9:42' is not a time");
        assertThatThrownBy(() -> calendar.datesOf(Map.of("due", "+400000000000d")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'+400000000000d' is not a date");
        assertThatThrownBy(() -> dates.date("year-end+1000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'year-end+1000000000' is not a date");
        assertThatThrownBy(() -> dates.date("+9223372036854775807d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'+9223372036854775807d' is not a date");
    }

    @Test
    void aNameIsAWordInCamelCaseThatTheExpressionsDoNotTake() {
        assertThatThrownBy(() -> calendar.datesOf(Map.of("Due", "+1d")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'Due' cannot name a date");
        assertThatThrownBy(() -> calendar.datesOf(Map.of("year", "+1d")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'year' cannot name a date");
    }

    @Test
    void aPlaceholderOfAnUnknownDateOrFormatAndABraceOfNoPlaceholderAreMistakes() {
        DemoCalendar.Dates dates = calendar.datesOf(Map.of("due", "+13d"));

        assertThatThrownBy(() -> dates.text("Pay by {dew:long}."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'dew' is not a date of the document");
        assertThatThrownBy(() -> dates.text("Pay by {due:lng}."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'{due:lng}' names no format of the set");
        assertThatThrownBy(() -> dates.text("Pay by {due}."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'{due}' is not a placeholder");
        assertThatThrownBy(() -> dates.text("Pay by {due:long."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a brace on line 1 opens or closes no placeholder: 'Pay by {due:long.'");
        assertThatThrownBy(() -> dates.text("# NOTE\n\nPay by {due:long}.\n   " + "x".repeat(90) + "}   \nThanks."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a brace on line 4 opens or closes no placeholder: '" + "x".repeat(79) + "…'");
        assertThatThrownBy(() -> dates.text("line1 {\nline2\nline3 }\nline4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a brace on line 1 opens or closes no placeholder: 'line1 {'");
    }

    @Test
    void aFormatOrALocaleThatCannotWriteADateIsAMistake() {
        assertThatThrownBy(() -> new DemoCalendar(TODAY, Locale.ENGLISH, Map.of("long", "MMMM d, {yyyy}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoCalendar(TODAY, Locale.ENGLISH, Map.of("stamp", "MMMM d, yyyy HH:mm")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the format 'stamp' is not a pattern of a date: 'MMMM d, yyyy HH:mm'");
        assertThatThrownBy(() -> new DemoCalendar(TODAY, Locale.ENGLISH, Map.of("long", "MMMM d, yyyy b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the format 'long' is not a pattern of a date: 'MMMM d, yyyy b'");
        assertThatThrownBy(() -> new DemoCalendar(TODAY, Locale.ENGLISH, Collections.singletonMap("long", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the format 'long' is not a pattern of a date: 'null'");
        assertThat(List.of("ru_RU", "rus", "russian", ""))
                .allSatisfy(locale -> assertThatThrownBy(() -> createCalendar(locale))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("the locale '%s' is not a language with names of the months", locale));
        assertThat(List.of("en", "ru", "ru-RU", "de", "es", "it"))
                .allSatisfy(locale -> assertThatCode(() -> createCalendar(locale)).doesNotThrowAnyException());
    }

    // the template reads the language of a set from its tag, which the calendar no longer sees
    private static void createCalendar(String locale) {
        new DemoSetTemplate(locale, ENGLISH, null, null, null).calendar(TODAY);
    }
}

package com.company.docai.demo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo sets keep the demo alive on every day of the two years after November 7, 2026, the day they were written
 * for.
 */
class DemoSetsTest {

    private static final LocalDate WRITTEN_FOR = LocalDate.of(2026, 11, 7);

    // two dates joined by a word of two letters: "from April 1 to May 31" in English, and so in Russian, Spanish and
    // Italian; German joins them by "bis"
    private static final Pattern RANGE = Pattern.compile("\\{(?!year:)(\\w+):(\\w+)},? (?:\\p{L}{2}|bis) \\{(\\w+):");

    // the check of a set before the load looks at the other problems once, and at its history on every day
    @ParameterizedTest
    @ValueSource(strings = {"en", "ru", "de", "es", "it"})
    void everyDayOfTwoYearsGivesAHistoryInThePastADateAheadAndAContractEndingInTheQuarter(String set)
            throws IOException {
        DemoSetTemplate template = DemoSetTemplate.read(set);

        for (LocalDate day = WRITTEN_FOR; day.isBefore(WRITTEN_FOR.plusYears(2)); day = day.plusDays(1)) {
            DemoSet generated = template.generate(set, day);
            List<DemoSet.Document> documents = new ArrayList<>();
            collectDocuments(generated.folders(), documents);
            LocalDate today = day;

            assertThat(generated.historyProblems(day.atStartOfDay())).as("the history on %s", day).isEmpty();
            assertThat(documents)
                    .as("a date of a field from %s on", day)
                    .anyMatch(document -> document.fields()
                            .values()
                            .stream()
                            .anyMatch(value -> isDateFrom(value, today)));
            assertThat(documents)
                    .as("a contract ending in the quarter of %s", day)
                    .anyMatch(document -> "Contract".equals(document.type())
                            && document.fields().containsKey("validUntil")
                            && isInQuarterOf(LocalDate.parse(document.fields().get("validUntil")), today));

            Map<String, List<String>> texts = documents.stream()
                    .flatMap(document -> document.versions().stream())
                    .filter(version -> version.text() != null)
                    .collect(Collectors.groupingBy(version -> textOf(set, version),
                            Collectors.mapping(DemoSet.Version::text, Collectors.toList())));

            assertThat(texts.values())
                    .as("a text of more than one document, written alike by each on %s", day)
                    .filteredOn(written -> written.size() > 1)
                    .isNotEmpty()
                    .allSatisfy(written -> assertThat(written)
                            .containsOnly(written.getFirst())
                            .noneMatch(String::isBlank));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ru", "de", "es", "it"})
    void aRangeOfATextKeepsItsOrderAndARangeOfDaysItsMonthOnEveryDayOfTwoYears(String set) throws IOException {
        DemoSetTemplate template = DemoSetTemplate.read(set);
        Map<DemoSetTemplate.Document, List<String>> ranges = new LinkedHashMap<>();
        collectRanges(set, template.folders(), ranges);

        assertThat(ranges).isNotEmpty();

        for (LocalDate day = WRITTEN_FOR; day.isBefore(WRITTEN_FOR.plusYears(2)); day = day.plusDays(1)) {
            DemoCalendar calendar = template.calendar(day);

            for (Map.Entry<DemoSetTemplate.Document, List<String>> document : ranges.entrySet()) {
                DemoCalendar.Dates dates = calendar.datesOf(document.getKey().dates());

                for (String range : document.getValue()) {
                    String[] parts = range.split(" ");
                    LocalDate start = dates.date(parts[0]);
                    LocalDate end = dates.date(parts[2]);
                    String format = template.formats().get(parts[1]);
                    String where = "%s of %s on %s".formatted(range, document.getKey().name(), day);

                    assertThat(start).as(where).isBeforeOrEqualTo(end);

                    if (writesNone(format, "yu")) {
                        assertThat(startAsRead(start, end)).as("%s, read without its year", where).isEqualTo(start);
                    }

                    if (writesNone(format, "ML")) {
                        assertThat(YearMonth.from(start)).as(where).isEqualTo(YearMonth.from(end));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru", "de", "es", "it"})
    void everySetDeclaresTheSameDatesForTheSameDocumentsAsTheEnglishOne(String set) throws IOException {
        assertThat(datesOf(DemoSetTemplate.read(set).folders(), ""))
                .isEqualTo(datesOf(DemoSetTemplate.read("en").folders(), ""));
    }

    private static boolean isDateFrom(String value, LocalDate today) {
        return value.matches("\\d{4}-\\d{2}-\\d{2}") && !LocalDate.parse(value).isBefore(today);
    }

    private static boolean isInQuarterOf(LocalDate date, LocalDate today) {
        return date.getYear() == today.getYear()
                && date.get(IsoFields.QUARTER_OF_YEAR) == today.get(IsoFields.QUARTER_OF_YEAR);
    }

    // a pattern writes a field when its letter stands outside the quotes
    private static boolean writesNone(String pattern, String letters) {
        return pattern.replaceAll("'[^']*'", "")
                .chars()
                .noneMatch(letter -> letters.indexOf(letter) >= 0);
    }

    // a reader puts a start without its year into the year of the end, or into the year before when it falls after it
    private static LocalDate startAsRead(LocalDate start, LocalDate end) {
        MonthDay day = MonthDay.from(start);
        LocalDate inTheYearOfTheEnd = day.atYear(end.getYear());

        return inTheYearOfTheEnd.isAfter(end) ? day.atYear(end.getYear() - 1) : inTheYearOfTheEnd;
    }

    // the ranges of the texts of each document: "start format end", with the format the text writes the start in
    private static void collectRanges(String set, List<DemoSetTemplate.Folder> folders,
                                      Map<DemoSetTemplate.Document, List<String>> ranges) throws IOException {
        for (DemoSetTemplate.Folder folder : folders) {
            for (DemoSetTemplate.Document document : folder.documents()) {
                for (DemoSetTemplate.Version version : document.versions()) {
                    if (version.text() == null) {
                        continue;
                    }

                    String text = DemoSetTemplate.text(set, version.text())
                            .getContentAsString(StandardCharsets.UTF_8);

                    Matcher range = RANGE.matcher(text);

                    while (range.find()) {
                        ranges.computeIfAbsent(document, key -> new ArrayList<>())
                                .add(range.group(1) + " " + range.group(2) + " " + range.group(3));
                    }
                }
            }

            collectRanges(set, folder.folders(), ranges);
        }
    }

    // the text of the set a version is made from: two documents share it when they name one file, or two files of it
    private static String textOf(String set, DemoSet.Version version) {
        return DemoSetTemplate.text(set, version.file())
                .getPath();
    }

    private static void collectDocuments(List<DemoSet.Folder> folders, List<DemoSet.Document> documents) {
        for (DemoSet.Folder folder : folders) {
            documents.addAll(folder.documents());
            collectDocuments(folder.folders(), documents);
        }
    }

    private static List<String> datesOf(List<DemoSetTemplate.Folder> folders, String place) {
        List<String> dates = new ArrayList<>();

        for (int index = 0; index < folders.size(); index++) {
            DemoSetTemplate.Folder folder = folders.get(index);
            String folderPlace = place + "/" + index;
            dates.add(folderPlace + " deleted " + (folder.deleted() == null ? null : folder.deleted().at()));

            for (int number = 0; number < folder.documents().size(); number++) {
                dates.add(folderPlace + ":" + number + " " + datesOf(folder.documents().get(number)));
            }

            dates.addAll(datesOf(folder.folders(), folderPlace));
        }

        return dates;
    }

    private static String datesOf(DemoSetTemplate.Document document) {
        // a field of a date holds a named date, the same in both sets, and another field holds words of the language
        Map<String, String> dateFields = new TreeMap<>();

        document.fields().forEach((code, value) -> {
            if (document.dates().containsKey(value)) {
                dateFields.put(code, value);
            }
        });

        List<String> uploads = document.versions()
                .stream()
                .map(DemoSetTemplate.Version::uploaded)
                .toList();

        List<String> comments = document.comments()
                .stream()
                .map(comment -> comment.at() + " " + comment.edited())
                .toList();

        return "dates %s, date %s, fields %s, uploads %s, comments %s, deleted %s".formatted(
                new TreeMap<>(document.dates()), document.date(), dateFields, uploads, comments,
                document.deleted() == null ? null : document.deleted().at());
    }
}


package com.codeguard.analysis;

import com.codeguard.dto.PullRequestFile;
import com.codeguard.dto.TestExecutionEvidence.ChangedTestResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The honesty contract for test execution, as pure functions.
 *
 * A run is PASSED only when the command exits successfully and
 * readable results positively confirm that tests executed without failures.
 */
public final class TestOutcome {

    private TestOutcome() {}

    public record Classification(
            String status,
            String reason,
            String message
    ) {}

    public static Classification classify(
            boolean timedOut,
            int exit,
            TestReportParser.Results tests,
            boolean offlineDependencyFailure,
            int timeoutSeconds
    ) {
        if (timedOut) {
            return new Classification(
                    "TIMED_OUT",
                    "TIMEOUT",
                    "The test command did not finish within "
                            + timeoutSeconds
                            + "s, so no pass/fail result exists."
            );
        }

        if (tests == null) {
            return new Classification(
                    "UNVERIFIED",
                    "RESULTS_UNREADABLE",
                    "Test results are unavailable, so CodeGuard cannot confirm that tests ran."
            );
        }

        int bad = tests.failures() + tests.errors();

        if (exit == 0) {
            if (tests.found() && tests.tests() > 0) {
                if (bad > 0) {
                    return new Classification(
                            "FAILED",
                            "",
                            bad + " of " + tests.tests() + " test(s) failed."
                    );
                }

                return new Classification(
                        "PASSED",
                        "",
                        tests.tests() + " test(s) executed at this commit: "
                                + tests.passed() + " passed, "
                                + tests.skipped() + " skipped, 0 failed."
                );
            }

            if (tests.found()) {
                return new Classification(
                        "NO_TESTS_EXECUTED",
                        "ZERO_TESTS",
                        "ZERO tests were executed; the build succeeded but the run cannot be marked PASSED."
                );
            }

            return new Classification(
                    "UNVERIFIED",
                    "RESULTS_UNREADABLE",
                    "The command exited 0 but its test results could not be read, "
                            + "so CodeGuard cannot confirm any test ran."
            );
        }

        if (tests.found() && tests.tests() > 0 && bad > 0) {
            return new Classification(
                    "FAILED",
                    "",
                    bad + " of " + tests.tests() + " test(s) failed."
            );
        }

        if (offlineDependencyFailure) {
            return new Classification(
                    "ERROR",
                    "DEPENDENCIES_UNAVAILABLE_OFFLINE",
                    "Dependencies could not be fetched because sandbox networking "
                            + "is disabled, so no tests ran."
            );
        }

        return new Classification(
                "ERROR",
                "COMMAND_FAILED_BEFORE_TESTS",
                "The build/test command failed with exit code "
                        + exit
                        + " before producing usable test results."
        );
    }

    public record Delta(
            List<String> fixed,
            List<String> newFailures
    ) {
        public static Delta none() {
            return new Delta(List.of(), List.of());
        }
    }

    /**
     * Compares failures before and after a patch.
     * Callers must ensure both reports are readable.
     */
    public static Delta delta(
            TestReportParser.Results before,
            TestReportParser.Results after
    ) {
        if (before == null || after == null
                || !before.found() || !after.found()) {
            return Delta.none();
        }

        Set<String> beforeFailures = new LinkedHashSet<>();
        Set<String> afterFailures = new LinkedHashSet<>();

        for (TestReportParser.Failed failure : before.failed()) {
            beforeFailures.add(id(failure));
        }

        for (TestReportParser.Failed failure : after.failed()) {
            afterFailures.add(id(failure));
        }

        List<String> fixed = new ArrayList<>();
        List<String> newFailures = new ArrayList<>();

        for (String failure : beforeFailures) {
            if (!afterFailures.contains(failure)) {
                fixed.add(failure);
            }
        }

        for (String failure : afterFailures) {
            if (!beforeFailures.contains(failure)) {
                newFailures.add(failure);
            }
        }

        return new Delta(fixed, newFailures);
    }

    private static String id(TestReportParser.Failed failure) {
        return (failure.suite() == null || failure.suite().isEmpty()
                ? ""
                : failure.suite() + ".") + failure.name();
    }

    /**
     * Determines whether changed JVM test files were represented
     * in the parsed JUnit XML report.
     */
    public static List<ChangedTestResult> mapChangedTests(
            List<PullRequestFile> changed,
            TestReportParser.Results tests,
            boolean executed
    ) {
        List<ChangedTestResult> out = new ArrayList<>();

        if (changed == null) {
            return out;
        }

        for (PullRequestFile fileInfo : changed) {
            if (fileInfo == null) {
                continue;
            }

            String filename = fileInfo.filename();

            if (filename == null
                    || !Code.isTestFile(filename)
                    || "removed".equalsIgnoreCase(fileInfo.status())) {
                continue;
            }

            if (!executed
                    || tests == null
                    || !tests.found()
                    || !Lang.of(filename).isJvm()
                    || !"JUNIT_XML".equals(tests.source())) {
                out.add(new ChangedTestResult(
                        filename, "UNKNOWN", 0, 0
                ));
                continue;
            }

            String base = filename.substring(
                    filename.lastIndexOf('/') + 1
            );
            int dot = base.lastIndexOf('.');

            if (dot > 0) {
                base = base.substring(0, dot);
            }

            int ran = 0;
            int bad = 0;
            boolean seen = false;

            for (TestReportParser.Suite suite : tests.suites()) {
                String name = suite.name();

                if (name != null
                        && (name.equals(base)
                        || name.endsWith("." + base)
                        || name.endsWith("$" + base))) {
                    seen = true;
                    ran += suite.tests();
                    bad += suite.failures() + suite.errors();
                }
            }

            String status = !seen || ran == 0
                    ? "NOT_EXECUTED"
                    : bad > 0 ? "FAILED" : "PASSED";

            out.add(new ChangedTestResult(
                    filename, status, ran, bad
            ));
        }

        return out;
    }
}

package com.codeguard.analysis;

import com.codeguard.dto.PullRequestFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The honesty contract: nothing but a counted, failure-free test run may ever be called PASSED. */
class TestOutcomeTest {

    private static TestReportParser.Results xml(int tests, int fail, int err, int skip, TestReportParser.Suite... suites) {
        return new TestReportParser.Results(tests, fail, err, skip, List.of(suites), List.of(), "JUNIT_XML");
    }

    @Test
    void passedRequiresExitZeroAndAtLeastOneCountedTestAndNoFailures() {
        assertEquals("PASSED", TestOutcome.classify(false, 0, xml(5, 0, 0, 1), false, 90).status());
    }

    @Test
    void exitZeroAloneIsNeverAPass() {
        // no report at all
        assertEquals("UNVERIFIED", TestOutcome.classify(false, 0, TestReportParser.Results.none(), false, 90).status());
        // report exists but ran zero tests (e.g. Maven with no test classes)
        var zero = TestOutcome.classify(false, 0, xml(0, 0, 0, 0), false, 90);
        assertEquals("NO_TESTS_EXECUTED", zero.status());
        assertTrue(zero.message().contains("ZERO"));
    }

    @Test
    void failuresAreFailedEvenWhenExitCodeIsZero() {
        assertEquals("FAILED", TestOutcome.classify(false, 0, xml(5, 1, 0, 0), false, 90).status());
        assertEquals("FAILED", TestOutcome.classify(false, 1, xml(5, 0, 2, 0), false, 90).status());
    }

    @Test
    void nonZeroExitWithoutResultsIsAnErrorNotAFailureOrPass() {
        var offline = TestOutcome.classify(false, 1, TestReportParser.Results.none(), true, 90);
        assertEquals("ERROR", offline.status());
        assertEquals("DEPENDENCIES_UNAVAILABLE_OFFLINE", offline.reason());
        var other = TestOutcome.classify(false, 2, TestReportParser.Results.none(), false, 90);
        assertEquals("ERROR", other.status());
        assertEquals("COMMAND_FAILED_BEFORE_TESTS", other.reason());
    }

    @Test
    void timeoutBeatsEverything() {
        assertEquals("TIMED_OUT", TestOutcome.classify(true, 0, xml(9, 0, 0, 0), false, 90).status());
    }

    @Test
    void noOutcomeOtherThanPassedEverClaimsAPass() {
        var cases = List.of(
                TestOutcome.classify(true, -1, TestReportParser.Results.none(), false, 90),
                TestOutcome.classify(false, 0, TestReportParser.Results.none(), false, 90),
                TestOutcome.classify(false, 0, xml(0, 0, 0, 0), false, 90),
                TestOutcome.classify(false, 3, TestReportParser.Results.none(), true, 90),
                TestOutcome.classify(false, 1, xml(4, 1, 0, 0), false, 90));
        for (var c : cases) {
            assertNotEquals("PASSED", c.status());
            assertFalse(c.message().toLowerCase().contains("tests passed"), c.message());
        }
    }

    @Test
    void changedTestFilesAreMatchedToExecutedSuites() {
        var results = xml(7, 1, 0, 0,
                new TestReportParser.Suite("com.shop.PriceServiceTest", 4, 0, 0, 0),
                new TestReportParser.Suite("com.shop.CartTest", 3, 1, 0, 0));
        var files = List.of(
                new PullRequestFile("src/test/java/com/shop/PriceServiceTest.java", "modified", 1, 1, "x"),
                new PullRequestFile("src/test/java/com/shop/CartTest.java", "modified", 1, 1, "x"),
                new PullRequestFile("src/test/java/com/shop/NewTest.java", "added", 9, 0, "x"),
                new PullRequestFile("src/main/java/com/shop/PriceService.java", "modified", 1, 1, "x"));
        var out = TestOutcome.mapChangedTests(files, results, true);
        assertEquals(3, out.size(), "production files are not listed");
        assertEquals("PASSED", out.get(0).status());
        assertEquals(4, out.get(0).testsRun());
        assertEquals("FAILED", out.get(1).status());
        assertEquals("NOT_EXECUTED", out.get(2).status(), "a changed test file with no matching executed suite must say so");
    }

    @Test
    void nonJvmOrNoResultsMeansUnknownNotPassed() {
        var files = List.of(new PullRequestFile("src/app.test.js", "modified", 1, 1, "x"));
        assertEquals("UNKNOWN", TestOutcome.mapChangedTests(files, xml(3, 0, 0, 0), true).get(0).status());
        var jvm = List.of(new PullRequestFile("src/test/java/ATest.java", "modified", 1, 1, "x"));
        assertEquals("UNKNOWN", TestOutcome.mapChangedTests(jvm, TestReportParser.Results.none(), true).get(0).status());
        assertEquals("UNKNOWN", TestOutcome.mapChangedTests(jvm, xml(3, 0, 0, 0), false).get(0).status());
    }

    @Test
    void unittestAndPytestConsoleOutputIsParsedIncludingFailedNames() {
        var ok = TestReportParser.parseConsole("....\n----------------------------------------------------------------------\nRan 4 tests in 0.001s\n\nOK\n");
        assertEquals(4, ok.tests()); assertEquals(0, ok.failures()); assertEquals("CONSOLE", ok.source());
        var bad = TestReportParser.parseConsole("FAIL: test_total (test_cart.CartTest)\nERROR: test_x (test_cart.CartTest)\n"
                + "----\nRan 5 tests in 0.002s\n\nFAILED (failures=1, errors=1, skipped=1)\n");
        assertEquals(5, bad.tests()); assertEquals(1, bad.failures()); assertEquals(1, bad.errors()); assertEquals(1, bad.skipped());
        assertEquals("test_cart.CartTest", bad.failed().get(0).suite());
        assertEquals("test_total", bad.failed().get(0).name());
        var none = TestReportParser.parseConsole("Ran 0 tests in 0.000s\n\nNO TESTS RAN\n");
        assertEquals(0, none.tests());
        assertTrue(none.found(), "a runner that reports zero tests is found-with-zero, never a pass");
        var py = TestReportParser.parseConsole("FAILED tests/test_a.py::test_one - assert 1 == 2\n=== 1 failed, 3 passed in 0.10s ===");
        assertEquals("tests/test_a.py", py.failed().get(0).suite());
        assertEquals("test_one", py.failed().get(0).name());
    }

    @Test
    void deltaShowsWhichTestsAPatchFixedAndWhichItBroke() {
        var before = new TestReportParser.Results(5, 2, 0, 0, List.of(), List.of(
                new TestReportParser.Failed("CartTest", "totalIsRight", ""), new TestReportParser.Failed("CartTest", "taxIsRight", "")), "JUNIT_XML");
        var after = new TestReportParser.Results(5, 1, 0, 0, List.of(), List.of(
                new TestReportParser.Failed("CartTest", "taxIsRight", ""), new TestReportParser.Failed("CartTest", "newlyBroken", "")), "JUNIT_XML");
        var d = TestOutcome.delta(before, after);
        assertEquals(List.of("CartTest.totalIsRight"), d.fixed());
        assertEquals(List.of("CartTest.newlyBroken"), d.newFailures());
        assertTrue(TestOutcome.delta(before, TestReportParser.Results.none()).fixed().isEmpty(), "no readable results -> no claims");
    }
}

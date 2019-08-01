package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.ResponseStringWrapper;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.jamoAutomator.domain.Report;
import org.apache.maven.plugin.logging.Log;

import java.util.Date;
import java.util.List;

import static com.jamosolutions.automator.help.Colorizer.*;

/**
 * Simple "counter holder" class. You should report all tests to it using one of four methods (according result of test).
 * <ul>
 * <li>{@link #recordSuccess(Execution, Report)}</li>
 * <li>{@link #recordTimeout(Execution, long)}</li>
 * <li>{@link #recordExecError(Device, TestCase, ResponseStringWrapper, long)}</li>
 * <li>{@link #recordTestFailure(Execution, Report, String)}</li>
 * </ul>
 */
public class ExecReport {
    public static final String COLOR_SUCCESS = "green,bold";
    public static final String COLOR_TIMEOUT = "faint,underline,red,bold";
    public static final String COLOR_EXECERR = "yellow,bold";
    public static final String COLOR_FAILURE = "red,bold";

    private final Log log;

    private int nbOfTimeouts = 0;
    private int nbOfExecErrors = 0;
    private int nbOfTestFailures = 0;
    private int nbOfSuccess = 0;

    public ExecReport(Log log) {
        this.log = log;
    }

    public void recordSuccess(Execution execution, Report report) {
        log.info(colorize(
                "@|" + COLOR_SUCCESS + " Success|@ test (" + device(execution.getDevice()) + ";" + testCase(execution.getTestCase()) + ")"
        ));
        log.debug(
                "Success debug info:\n" +
                        "wallDuration(our): " + (System.currentTimeMillis() - execution.getStartTimeMillis()) + "\n" +
                        "executeRequestFinishedAt(our): " + new Date(execution.getStartTimeMillis()) + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "report.getCreationDate: " + report.getCreationDate() + "\n" +
                        "report.getEndDate: " + report.getEndDate() + "\n" +
                        "report.getBuildNumber: " + report.getBuildNumber() + "\n" +
                        "report.getKeyString: " + report.getKeyString() + "\n" +
                        "report.getExecutionId: " + report.getExecutionId()
        );
        this.nbOfSuccess++;
    }

    public void recordTimeout(Execution execution, long durationTillNowMs) {
        log.warn(colorize(
                "@|" + COLOR_TIMEOUT + " Timeout test execution|@ test (" + device(execution.getDevice()) + ";" + testCase(execution.getTestCase()) + ")"
        ));
        log.debug(
                "Timeout debug info:\n" +
                        "wallDuration(our): " + (System.currentTimeMillis() - execution.getStartTimeMillis()) + "\n" +
                        "executeRequestFinishedAt(our): " + new Date(execution.getStartTimeMillis()) + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "execution.getExecutionId: " + execution.getExecutionId() + "\n" +
                        "durationTillNowMs(calculated): " + durationTillNowMs + "\n" +
                        "execution.getTestCase().getTimeout(): " + execution.getTestCase().getTimeout() + " -> " + (60 * 1000 * execution.getTestCase().getTimeout()) + "\n"
        );
        nbOfTimeouts++;
    }

    public void recordExecError(Device device, TestCase testCase, ResponseStringWrapper response, long requestStartTime) {
        log.warn(colorize(
                "@|" + COLOR_EXECERR + " error while executing|@ test (" + device(device) + ";" + testCase(testCase) + ")"
        ));
        log.debug(
                "executing error debug info:\n" +
                        // TODO here is disproportion, because counting also request time to jamo and back, despite it is not included in other wallDurations!
                        "wallDuration(our): " + (System.currentTimeMillis() - requestStartTime) + "\n" +
                        "requestStartTime(our): " + requestStartTime + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "response:" + response
        );
        this.nbOfExecErrors++;
    }

    public void recordTestFailure(Execution execution, Report report, String linkToReport) {
        log.warn(colorize(
                "@|" + COLOR_FAILURE + " Failure test execution|@ test (" + device(execution.getDevice()) + ";" + testCase(execution.getTestCase()) + "+ reportLink: " + linkToReport + " )"
        ));
        log.debug(
                "Failure test debug info:\n" +
                        "wallDuration(our): " + (System.currentTimeMillis() - execution.getStartTimeMillis()) + "\n" +
                        "executeRequestFinishedAt(our): " + new Date(execution.getStartTimeMillis()) + "\n" +
                        "currentTime(our): " + new Date() + "\n" +
                        "report.getCreationDate: " + report.getCreationDate() + "\n" +
                        "report.getEndDate: " + report.getEndDate() + "\n" +
                        "report.getBuildNumber: " + report.getBuildNumber() + "\n" +
                        "report.getKeyString: " + report.getKeyString() + "\n" +
                        "report.getExecutionId: " + report.getExecutionId() + "\n" +
                        "report.getStatus: " + report.getStatus() + "\n" +
                        "linkToReport: " + linkToReport
        );
        this.nbOfTestFailures++;
    }

    public int getNbOfExecErrors() {
        return nbOfExecErrors;
    }

    public int getNbOfSuccess() {
        return nbOfSuccess;
    }

    public int getNbOfTestFailures() {
        return nbOfTestFailures;
    }

    public int getNbOfTimeouts() {
        return nbOfTimeouts;
    }

    public boolean isWithoutNegativeResults() {
        return this.nbOfExecErrors == 0 && this.nbOfExecErrors == 0 && this.nbOfTestFailures == 0;
    }

    /**
     * Sums all executions (successfull or not). It returns sum of all four internal counters.
     *
     * @return number of tests in executed suite. it counts also things like non-existent reports (which does not executed)
     */
    public int getTotalExecutionsAtemps() {
        return this.nbOfSuccess + this.nbOfTestFailures + this.nbOfExecErrors + this.nbOfTimeouts;
    }

    /**
     * @return sum of execution errors (nonexistent test,...) and test timeouts (no report found within time)
     */
    public int getNbOfErrors() {
        return this.nbOfExecErrors + this.nbOfTimeouts;
    }

    public int getNbOfAllNegativeResults() {
        return this.nbOfExecErrors + this.nbOfTimeouts + this.nbOfTestFailures;
    }

    public void logProgressReport(int waitRound, List<FutureExecution> executionsToDoFlight, List<Execution> executionsInFlight) {
        if(waitRound == 0) {
            log.info(colorize(
                    "\t@|bg_black,bold,cyan status legend|@(" +
                            "@|bold,underline TotalExecutionsAtemps done|@(" +
                            "@|" + ExecReport.COLOR_SUCCESS + " success|@/" +
                            "@|" + ExecReport.COLOR_FAILURE + " failures|@/" +
                            "@|" + ExecReport.COLOR_EXECERR + " execErrs|@/" +
                            "@|" + ExecReport.COLOR_TIMEOUT + " timeouts|@), " +
                            "@|bold XX waiting|@, " +
                            "@|italic,underline XX inProcess|@)."
            ));
        }
        log.info(colorize(
                "\t@|bg_black,bold,cyan status|@(" +
                        "@|bold,underline " + this.getTotalExecutionsAtemps() + " done|@(" +
                        "@|" + ExecReport.COLOR_SUCCESS + " " + this.getNbOfSuccess() + "|@/" +
                        "@|" + ExecReport.COLOR_FAILURE + " " + this.getNbOfTestFailures() + "|@/" +
                        "@|" + ExecReport.COLOR_EXECERR + " " + this.getNbOfExecErrors() +"|@/" +
                        "@|" + ExecReport.COLOR_TIMEOUT + " " + this.getNbOfTimeouts() +"|@), " +
                        "@|bold " + executionsToDoFlight.size() + " waiting|@, " +
                        "@|italic,underline " + executionsInFlight.size() + " inProcess|@) " +
                        "I have waited about " + (waitRound * 5) + " seconds for reports till now. Going to wait another 5 seconds."
        ));
    }

    public void logSummaryReport() {
        String headlineColor = "red";
        if(this.isWithoutNegativeResults()) {
            headlineColor = "green";
        } else {
            log.warn("There was some negative test results (" + this.getNbOfAllNegativeResults() + ")! See info report bellow for summary or have a look at log or test report file.");
        }
        log.info(colorize(
                "@|bold," + headlineColor + " SUMMARY REPORT|@\n" +
                        "\t@|" + ExecReport.COLOR_SUCCESS + " " +
                        "recordSuccess|@ : @|bold " + this.getNbOfSuccess() + "|@\t (number of test executions with successful execution and asserts)\n" +
                        "\t@|" + ExecReport.COLOR_FAILURE + " " +
                        "failure|@ : @|bold " + this.getNbOfTestFailures() + "|@\t (number of tests with report with some failure. Failed on test asserts or failure during test execution)\n" +
                        "\t@|" + ExecReport.COLOR_EXECERR + " " +
                        "exec err|@: @|bold " + this.getNbOfExecErrors() + "|@\t (number of executions failed. It counts executions of nonexistent tests, problems with authentication to jamo during test executions and so on)\n" +
                        "\t@|" + ExecReport.COLOR_TIMEOUT + " " +
                        "recordTimeout|@ : @|bold " + this.getNbOfTimeouts() + "|@\t (report not found within recordTimeout after test execution started)\n" +
                        "@|bold " +
                        "total|@   : @|bold " + this.getTotalExecutionsAtemps() + "|@"
        ));
    }
}


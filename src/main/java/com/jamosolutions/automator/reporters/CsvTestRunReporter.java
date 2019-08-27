package com.jamosolutions.automator.reporters;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.automator.help.TestRun;
import com.jamosolutions.automator.help.TestRunReporterListener;
import com.jamosolutions.jamoAutomator.domain.Report;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Note: You have to call {@link #prepareOutputFile()} method before any event of finished {@link TestRun} happens!
 */
public class CsvTestRunReporter implements TestRunReporterListener, AutoCloseable {
    private final String fileName;
    private PrintWriter printWriter = null;

    public CsvTestRunReporter(String fileName) {
        this.fileName = fileName;
    }

    public void prepareOutputFile() throws IOException {
        FileWriter fileWriter = new FileWriter(fileName);
        this.printWriter = new PrintWriter(fileWriter);
        this.printWriter.println("device;testCase;result;currentTimeMillis;getRequestStartTime;getStartTimeMillis;getExecutionId;getErrorsWhileGettingReport;getBuildNumber;getCreationDate;getEndDate;willBeRetried");
    }

    @Override
    public void finishTestRunExecution(TestRun testRun, boolean isFinalRunForPlannedTestRun) {
        final boolean willBeRetried = !isFinalRunForPlannedTestRun;
        final Report report = testRun.getReport();
        final Device device = testRun.getPlannedTestRun().getDevice();
        final TestCase testCase = testRun.getPlannedTestRun().getTestCase();
        final long now = System.currentTimeMillis();
        switch (testRun.getExecutionOutcome()) {
            case SUCCESS:
                printWriter.println("" + device.getName() + ";" + testCase.getName() + ";" + "success" + ";" + now + ";" + testRun.getRequestStartTime() + ";" + testRun.getExecRequestReturnedTimeMillis() + ";" + testRun.getExecutionId() + ";" + testRun.getErrorsWhileGettingReport() + ";" + report.getBuildNumber() + ";" + report.getCreationDate() + "+" + report.getEndDate() + ";false");
                break;
            case FAILURE:
                printWriter.println("" + device.getName() + ";" + testCase.getName() + ";" + "failure" + ";" + now + ";" + testRun.getRequestStartTime() + ";" + testRun.getExecRequestReturnedTimeMillis() + ";" + testRun.getExecutionId() + ";" + testRun.getErrorsWhileGettingReport() + ";" + report.getBuildNumber() + ";" + report.getCreationDate() + "+" + report.getEndDate() + ";" + willBeRetried);
                break;
            case TIMEOUT:
                printWriter.println("" + device.getName() + ";" + testCase.getName() + ";" + "timeout" + ";" + now + ";" + testRun.getRequestStartTime() + ";" + testRun.getExecRequestReturnedTimeMillis() + ";" + testRun.getExecutionId() + ";" + testRun.getErrorsWhileGettingReport() + ";;;" + willBeRetried);
                break;
            case EXECERR:
                printWriter.println("" + device.getName() + ";" + testCase.getName() + ";" + "execError" + ";" + now + ";" + testRun.getRequestStartTime() + ";;;;;;" + willBeRetried);
                break;
        }
    }

    @Override
    public void close() {
        this.printWriter.close();
    }
}

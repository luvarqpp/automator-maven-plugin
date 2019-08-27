package com.jamosolutions.automator.reporters;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.automator.help.ExecutionOutcome;
import com.jamosolutions.automator.help.TestRun;
import com.jamosolutions.automator.help.TestRunReporterListener;
import com.jamosolutions.jamoAutomator.domain.Report;
import org.apache.maven.plugin.logging.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Text;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;

/**
 * Note thant this reporter will record only last {@link TestRun} instance for each {@link com.jamosolutions.automator.help.PlannedTestRun}
 * instance (according second parameter in {@link TestRunReporterListener#finishTestRunExecution(TestRun, boolean)})
 * notification method.
 * <p>
 * Note: time of running reports will be wall time between any (not only reported) received {@link TestRun} instances.
 * In case that first {@link TestRun} will be retried, you can see larger "time" in final report than it seems to be
 * from particular test runs reported in file.
 */
public class JunitXmlTestRunReporter implements TestRunReporterListener, AutoCloseable {
    private final Log log;
    private final OnlineLogTestRunExecReport onlineLogTestRunExecReport;
    private final String reportsDir;
    private final Element testsuiteElement;
    private final Document doc;

    private String testSuiteName;
    private long earliestTestRunStartTime = Long.MAX_VALUE;
    private long latestTestRunFinishTime = 0L;

    public JunitXmlTestRunReporter(Log log, OnlineLogTestRunExecReport onlineLogTestRunExecReport, String reportsDir) {
        this.log = log;
        this.onlineLogTestRunExecReport = onlineLogTestRunExecReport;
        this.reportsDir = reportsDir;

        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = null;
        try {
            docBuilder = docFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Unexpected error = " + e.getMessage(), e);
        }
        doc = docBuilder.newDocument();
        testsuiteElement = doc.createElement("testsuite");
        doc.appendChild(testsuiteElement);
    }

    @Override
    public void finishTestRunExecution(TestRun testRun, boolean isFinalRunForPlannedTestRun) {
        this.earliestTestRunStartTime = Long.min(this.earliestTestRunStartTime, testRun.getRequestStartTime());
        this.latestTestRunFinishTime = Long.max(this.latestTestRunFinishTime, testRun.getFinishedTimeMillis());
        if (!isFinalRunForPlannedTestRun) {
            // we want to record only final executions of TestRuns
            return;
        }
        final Report report = testRun.getReport();
        final Device device = testRun.getPlannedTestRun().getDevice();
        final TestCase testCase = testRun.getPlannedTestRun().getTestCase();
        switch (testRun.getExecutionOutcome()) {
            case SUCCESS:
            case FAILURE:
                Element testcaseElementSuccOrFail = doc.createElement("testcase");
                long durationFromReportMs = report.getEndDate().getTime() - report.getCreationDate().getTime();
                testcaseElementSuccOrFail.setAttribute("time", "" + (durationFromReportMs / 1000));
                testcaseElementSuccOrFail.setAttribute("name", testCase.getName());
                testcaseElementSuccOrFail.setAttribute("classname", "com.jamosolutions." + testSuiteName + "." + device.getName());
                // TODO hardcoded https://jamo-release.appspot.com !!! Use geturl somehow
                final String linkToReport = "https://jamo-release.appspot.com" + "/index.html?reportDetail=" + report.getKeyString();
                if (testRun.getExecutionOutcome() == ExecutionOutcome.SUCCESS) {
                    Element systemoutElement = doc.createElement("system-out");
                    Text okMessageDetail = doc.createTextNode("For more detail click " + linkToReport);
                    systemoutElement.appendChild(okMessageDetail);
                    testcaseElementSuccOrFail.appendChild(systemoutElement);
                } else {
                    Element failureElement = doc.createElement("failure");
                    failureElement.setAttribute("message", "The test case did not succeed.");
                    Text errorMessageDetail = doc.createTextNode("For more detail click " + linkToReport);
                    failureElement.appendChild(errorMessageDetail);
                    testcaseElementSuccOrFail.appendChild(failureElement);
                }
                testsuiteElement.appendChild(testcaseElementSuccOrFail);
                break;
            case TIMEOUT:
                long durationTillNowMs = (testRun.getFinishedTimeMillis() - testRun.getExecRequestReturnedTimeMillis());
                Element testcaseElementTimeout = doc.createElement("testcase");
                testcaseElementTimeout.setAttribute("time", "" + (durationTillNowMs / 1000.0));
                testcaseElementTimeout.setAttribute("name", testRun.getPlannedTestRun().getTestCase().getName());
                testcaseElementTimeout.setAttribute("classname", "com.jamosolutions." + this.testSuiteName + "." + testRun.getPlannedTestRun().getDevice().getName());
                Element timeoutErrorElement = doc.createElement("error");
                timeoutErrorElement.setAttribute(
                        "message",
                        "could not find any report within " + (durationTillNowMs / 1000 / 60) + " minutes. You can try later " +
                                " at TODO Implement"// TODO implement generating url for getting report json (where you can find report.getKeyString() and use to retrieve actual report) // + jamoAutomatorClient.getReportUri(testRun.getExecutionId())
                );
                testcaseElementTimeout.appendChild(timeoutErrorElement);
                testsuiteElement.appendChild(testcaseElementTimeout);
                break;
            case EXECERR:
                Element testcaseElementExecErr = doc.createElement("testcase");
                testcaseElementExecErr.setAttribute("time", "" + (testRun.getExecRequestReturnedTimeMillis() - testRun.getRequestStartTime()));
                testcaseElementExecErr.setAttribute("name", testRun.getPlannedTestRun().getTestCase().getName());
                testcaseElementExecErr.setAttribute("classname", "com.jamosolutions." + this.testSuiteName + "." + testRun.getPlannedTestRun().getDevice().getName());
                Element execErrErrorElement = doc.createElement("error");
                execErrErrorElement.setAttribute("message", testRun.getExecRequestResponse().getMessage());
                testcaseElementExecErr.appendChild(execErrErrorElement);
                testsuiteElement.appendChild(testcaseElementExecErr);
                break;
        }
    }

    @Override
    public void close() {
        OnlineLogTestRunExecReport er = this.onlineLogTestRunExecReport;
        testsuiteElement.setAttribute("errors", "" + er.getNbOfErrors());
        testsuiteElement.setAttribute("failures", "" + er.getNbOfTestFailures());
        long totalDuration = (this.latestTestRunFinishTime - this.earliestTestRunStartTime) / 1000;
        log.info("Wall time of running reports is " + totalDuration + " seconds.");
        testsuiteElement.setAttribute("time", "" + totalDuration);
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = null;
        try {
            transformer = transformerFactory.newTransformer();
        } catch (TransformerConfigurationException e) {
            throw new RuntimeException("Unexpected exception " + e.getMessage(), e);
        }
        DOMSource source = new DOMSource(doc);
        File reportFile = new File(reportsDir, "TEST-com.jamoautomator." + testSuiteName + ".xml");
        StreamResult result = new StreamResult(reportFile);
        try {
            transformer.transform(source, result);
        } catch (TransformerException e) {
            throw new RuntimeException("could not build junit xml document! Unexpected exception " + e.getMessage(), e);
        }
    }

    public void setTestSuiteName(String testSuiteName) {
        testsuiteElement.setAttribute("name", testSuiteName);
        this.testSuiteName = testSuiteName;
    }
}

package com.jamosolutions.automator;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.automator.domain.TestSuite;
import com.jamosolutions.automator.help.*;
import com.jamosolutions.automator.reporters.CsvTestRunReporter;
import com.jamosolutions.automator.reporters.JunitXmlTestRunReporter;
import com.jamosolutions.automator.reporters.OnlineLogTestRunExecReport;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import static com.jamosolutions.automator.help.Colorizer.colorize;

/**
 * Run tests on given devices. List of tests/devices is taken from "descriptor" parameter.
 */
@Mojo(name = "run", requiresOnline = true)
public class JamoAutomatorMojo extends AbstractMojo {
    public static final long WAIT_ROUND_DURATION_MS = 5000;
	//show tests in progress
	//https://wiki.jenkins-ci.org/display/JENKINS/Test+In+Progress+Plugin
	//maven version plugin
	//http://www.mojohaus.org/versions-maven-plugin/
	//mvn versions:use-latest-versions -DallowSnapshots=true
	//mvn help:describe -Dplugin=com.jamosolutions:jamoautomator-maven-plugin:1.0.7-SNAPSHOT -Ddetail
	//https://maven.apache.org/plugin-testing/maven-plugin-testing-harness/getting-started/index.html

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

	/**
	 * Default value for test descriptor (xml file with list of devices and tests to run on them) is
	 * <b>src/main/resources/testsuite.xml</b>.
	 */
	@Parameter(defaultValue = "${suite}")
	private String descriptor;

	/**
	 * Determines, if plugin should automatically retest failed tests. This means if you set this parameter to true,
	 * plugin will add tests with negative outcome to "executionsToDoFlight" queue (at most once).
	 *
	 * As test with negative outcome is considered:
	 * <ul>
	 *     <li>Finished test with failed result</li>
	 *     <li>Execute attempt with fail (wrong test name, target device offline, ...)</li>
	 *     <li>Timeout-ed test execution</li>
	 * </ul>
	 *
	 * NOTE: Some negative outcomes are retried uselessly. For example failed execution attempt with wrong name of test.
	 */
	@Parameter(defaultValue = "false")
	private boolean retest;

	public JamoAutomatorMojo() {
	}

	public JamoAutomatorMojo(boolean retest) {
		this.retest = retest;
	}

	protected File getReportDirectory(File baseDirAbsolutePath) {
		File reportFolder = new File(baseDirAbsolutePath, "target/surefire-reports");
		if (!reportFolder.exists()) {
			getLog().info("creating report folder");
			reportFolder.mkdirs();
		}
		return new File(baseDirAbsolutePath, "target/surefire-reports");
	}

	public void execute() throws MojoExecutionException {
		final Log log = getLog();
		log.info(colorize("Executing @|bold,blue automator-maven-plugin|@ at @|FAINT,blue " + new Date() + "|@."));
		log.info("add -X parameter to see debug log level with additional info about progress and internal logic.");
		if (descriptor == null) {
			descriptor = "testsuite";
		}

        final File testSuiteFile;

        File baseDir;
        if (this.project == null) {
            baseDir = new File(".");
            testSuiteFile = new File(baseDir.getAbsolutePath() + "/src/test/resources", descriptor + ".xml");
            log.info("Going to set current dir as baseDir. baseDir=" + baseDir.getAbsolutePath());
        } else {
            baseDir = this.project.getBasedir();
            testSuiteFile = new File(baseDir.getAbsolutePath() + "/src/main/resources", descriptor + ".xml");
        }


        log.debug("Going to use suite with name \"" + descriptor + "\". Full path is \"" + testSuiteFile.getAbsolutePath() + "\".");
        if (false == testSuiteFile.exists()) {
            log.warn(
                    "There was no testsuite file found. Set proper \"suite\" file (by default testsuite.xml). Currently looking for file here: \"" +
                            testSuiteFile.getAbsolutePath() + "\". Going to do nothing."
            );
            return;
        }
		long startMillis = System.currentTimeMillis();
		String testSuiteName = "";
		OnlineLogTestRunExecReport onlineLogTestRunExecReport = new OnlineLogTestRunExecReport(log);
		try (
				JunitXmlTestRunReporter junitXmlTestRunReporter = new JunitXmlTestRunReporter(log, onlineLogTestRunExecReport, getReportDirectory(baseDir).getAbsolutePath());
				CsvTestRunReporter csvTestRunReporter = new CsvTestRunReporter("target/testRunsRaw.csv")
		) {
            //parse the testsuite file
            JAXBContext jaxbContext = JAXBContext.newInstance(TestSuite.class);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
            TestSuite testSuite = (TestSuite) jaxbUnmarshaller.unmarshal(testSuiteFile);
            //build the xml test suite document
            //http://help.catchsoftware.com/display/ET/JUnit+Format
			testSuiteName = testSuite.getName();
			junitXmlTestRunReporter.setTestSuiteName(testSuiteName);

			final JamoAutomatorClient jamoAutomatorClient = new JamoAutomatorClient(log, testSuite.getCredentials(), testSuite.getUrl());
            final TestOrchestrator testOrchestrator = new TestOrchestrator(jamoAutomatorClient, log, this.retest ? 1 : 0);

            log.debug("Going to pre-fill all future executions.");
			final List<Device> allDevices = testSuite.getDevices();
			for (Device device : allDevices) {
                for (TestCase testCase : device.getTestCases()) {
                    testOrchestrator.addTestForExecution(new PlannedTestRun(device, testCase));
                }
            }
            // log.debug(colorize("there are now @|bold " + executionsToDoFlight.size() + "|@ future executions requests."));

            int waitRound = 0;
			final List<TestRunReporterListener> testRunReporterListeners = testOrchestrator.getTestRunReporterListeners();
			testRunReporterListeners.add(junitXmlTestRunReporter);
			testRunReporterListeners.add(onlineLogTestRunExecReport);
			testRunReporterListeners.add(csvTestRunReporter);
			try {
				csvTestRunReporter.prepareOutputFile();
			} catch (IOException ex) {
				throw new MojoExecutionException("Problem while creating/opening/accessing file testRunsRaw.csv. ex=" + ex.getMessage(), ex);
			}
			onlineLogTestRunExecReport.logProgressReport(waitRound, testOrchestrator);
			while (testOrchestrator.isStillSomethingNeedToBeDone()) {
                testOrchestrator.getReportsForRunningTests();
                testOrchestrator.checkTimeoutsOnRunningTests();
            	testOrchestrator.checkForIdleDevicesAndUseThem();
                // print progress at 0, 30 and 60 seconds and than each minute
                if (waitRound == 6 || (waitRound % 12 == 0)) {
                    // TODO pass orchestrator to exec report to have data...
                    onlineLogTestRunExecReport.logProgressReport(waitRound, testOrchestrator);
                }
                Thread.sleep(WAIT_ROUND_DURATION_MS);
                waitRound++;
            } // end of while there is executing, or to be executed.
        } catch (JAXBException e) {
            log.error("could not parse the descriptor file " + descriptor, e);
        } catch (InterruptedException e) {
            log.info("the test suite has been interrupted", e);
        } finally {
			onlineLogTestRunExecReport.logSummaryReport();
		}
	}

	/**
	 * Returns true, only if given execution is "solved". If there is retry needed for timeouted
	 * @param log
	 * @param testRun
	 * @return	returns true, if report has been found and processed. false otherwise.
	 */
	private boolean isTimeouted(Log log, TestRun testRun) {
		return false;
	}

}
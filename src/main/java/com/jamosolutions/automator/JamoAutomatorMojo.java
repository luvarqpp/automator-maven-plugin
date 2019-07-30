package com.jamosolutions.automator;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Text;

import com.jamosolutions.automator.domain.Credentials;
import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.ResponseStringWrapper;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.automator.domain.TestSuite;
import com.jamosolutions.jamoAutomator.domain.LoginStatus;
import com.jamosolutions.jamoAutomator.domain.Report;
import static org.fusesource.jansi.Ansi.*;

@Mojo(name = "run")
public class JamoAutomatorMojo extends AbstractMojo {
	//show tests in progress
	//https://wiki.jenkins-ci.org/display/JENKINS/Test+In+Progress+Plugin
	//maven version plugin
	//http://www.mojohaus.org/versions-maven-plugin/
	//mvn versions:use-latest-versions -DallowSnapshots=true

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

	@Parameter(defaultValue = "${suite}")
	private String descriptor;

	/**
	 * Holds last debug log string for each device. This is used to suppress logging same messages in basic scenarios.
	 *
	 * @see #logDebugForDevice(Log, Device, String)
	 */
	private final Map<Device, String> lastDebugLogPerDevice = new HashMap<>();

	protected File getReportDirectory(File baseDirAbsolutePath) {
		File reportFolder = new File(baseDirAbsolutePath, "target/surefire-reports");
		if (!reportFolder.exists()) {
			getLog().info("creating report folder");
			reportFolder.mkdirs();
		}
		return new File(baseDirAbsolutePath, "target/surefire-reports");
	}

	public static String colorize(String text) {
		return ansi().render(text).toString();
	}

	public static String device(Device device) {
		return "@|blue,bold " + device.getName() + "|@";
	}
	public static String deviceUdid(Device device) {
		return "@|faint,blue,bold " + device.getUdid() + "|@";
	}
	public static String testCase(TestCase tc) {
		return "@|faint,blue,bold " + tc.getName() + "|@";
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
        ExecReport er = new ExecReport(log);
        try {
			long startMillis = System.currentTimeMillis();
            //parse the testsuite file
            JAXBContext jaxbContext = JAXBContext.newInstance(TestSuite.class);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
            TestSuite testSuite = (TestSuite) jaxbUnmarshaller.unmarshal(testSuiteFile);
            //build the xml test suite document
            //http://help.catchsoftware.com/display/ET/JUnit+Format
            File reportFile = new File(getReportDirectory(baseDir).getAbsolutePath(), "TEST-com.jamoautomator." + testSuite.getName() + ".xml");
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
            Document doc = docBuilder.newDocument();
            Element testsuiteElement = doc.createElement("testsuite");
            doc.appendChild(testsuiteElement);
            testsuiteElement.setAttribute("name", testSuite.getName());
            UserKeyAndToken loginResult = login(testSuite.getCredentials(), testSuite.getUrl());

            List<FutureExecution> executionsToDoFlight = new ArrayList<>();

            log.debug("Going to prefill all future executions.");
            for (Device device : testSuite.getDevices()) {
                int idx = 0;
                for (TestCase testCase : device.getTestCases()) {
                    executionsToDoFlight.add(new FutureExecution(device, testCase));
                }
            }
            log.debug("there are now @|bold " + executionsToDoFlight.size() + "|@ future executions requests.");

            int waitRound = 0;
            List<Execution> executionsInFlight = new ArrayList<>();
            while (executionsToDoFlight.size() > 0 || executionsInFlight.size() > 0) {
                Set<Device> devicesWithSingleExecutionOnIt = new HashSet<>();

                for (Iterator<Execution> iterator = executionsInFlight.iterator(); iterator.hasNext();) {
                    Execution execution = iterator.next();
                    long durationTillNowMs = (System.currentTimeMillis() - execution.getStartTimeMillis());
                    if (durationTillNowMs > execution.getTestCase().getTimeout() * 60 * 1000) {
                        iterator.remove();
                        er.recordTimeout(execution, durationTillNowMs);
                        Element testcaseElement = doc.createElement("testcase");
                        testcaseElement.setAttribute("time", "" + (durationTillNowMs / 1000.0));
                        testcaseElement.setAttribute("name", execution.getTestCase().getName());
                        testcaseElement.setAttribute("classname", "com.jamosolutions." + testSuite.getName() + "." + execution.getDevice().getName());
                        Element errorElement = doc.createElement("error");
                        errorElement.setAttribute(
                                "message",
                                "could not find any report within " + (durationTillNowMs / 1000 / 60) + " minutes. You can try later " +
                                        " at " + getReportUri(execution.getExecutionId(), loginResult.authToken, testSuite.getUrl())
                        );
                        testcaseElement.appendChild(errorElement);
                        testsuiteElement.appendChild(testcaseElement);
                    } else {
                        Report report = getReport(execution.getExecutionId(), loginResult.authToken, testSuite.getUrl());
                        if (report != null) {
                            Element testcaseElement = doc.createElement("testcase");
                            long durationFromReportMs = report.getEndDate().getTime() - report.getCreationDate().getTime();
                            testcaseElement.setAttribute("time", "" + (durationFromReportMs/ 1000));
                            testcaseElement.setAttribute("name", execution.getTestCase().getName());
                            testcaseElement.setAttribute("classname", "com.jamosolutions." + testSuite.getName() + "." + execution.getDevice().getName());
                            if (report.getStatus() != 0) {
                                final String linkToReport = testSuite.getUrl() + "/index.html?reportDetail=" + report.getKeyString();
                                er.recordTestFailure(execution, report, linkToReport);
                                Element failureElement = doc.createElement("failure");
                                failureElement.setAttribute("message", "The test case did not succeed.");
                                Text errorMessageDetail = doc.createTextNode("For more detail click " + linkToReport);
                                failureElement.appendChild(errorMessageDetail);
                                testcaseElement.appendChild(failureElement);
                            } else {
                                Element systemoutElement = doc.createElement("system-out");
                                Text okMessageDetail = doc.createTextNode("For more detail click " + testSuite.getUrl() + "/index.html?reportDetail="
                                        + report.getKeyString());
                                systemoutElement.appendChild(okMessageDetail);
                                testcaseElement.appendChild(systemoutElement);
                                er.recordSuccess(execution, report);
                            }
                            testsuiteElement.appendChild(testcaseElement);
                            iterator.remove();
                        } else {
							logDebugForDevice(
									log,
									execution.getDevice(),
									colorize(
											"Device " + device(execution.getDevice()) + " have still running test " +
													"@|blue " + execution.getTestCase() + "|@ on it (no report found with id " +
													"@|blue " + execution.getExecutionId() + "|@). Going to wait."

									)
							);
							devicesWithSingleExecutionOnIt.add(execution.device);
                        }
                    }
                } // end of for iterator through executionsInFlight

                Set<Device> idleDevices = new HashSet<>(testSuite.getDevices());
                idleDevices.removeAll(devicesWithSingleExecutionOnIt);

                for (Device idleDevice: idleDevices) {
                    Optional<FutureExecution> newTest = popAnotherTestForDevice(executionsToDoFlight, idleDevice);
                    if(newTest.isPresent()) {
                        FutureExecution needToExecute = newTest.get();
                        log.info(colorize("Device " + device(idleDevice) + " have no awaiting report for now, going to execute " + testCase(needToExecute.testCase) + " on it."));
                        // request test execution
                        final TestCase testCaseToExecute = needToExecute.getTestCase();
                        ResponseStringWrapper response = runTestCase(idleDevice, testCaseToExecute, loginResult, testSuite.getUrl(), log);
                        if (response.isSuccess()) {
                            executionsInFlight.add(new Execution(response.getMessage(), idleDevice, testCaseToExecute));
                        } else {
                            er.recordExecError(idleDevice, testCaseToExecute, response);
                            Element testcaseElement = doc.createElement("testcase");
                            testcaseElement.setAttribute("time", "" + 0);
                            testcaseElement.setAttribute("name", testCaseToExecute.getName());
                            testcaseElement.setAttribute("classname", "com.jamosolutions." + testSuite.getName() + "." + needToExecute.getDevice().getName());
                            Element errorElement = doc.createElement("error");
                            errorElement.setAttribute("message", response.getMessage());
                            testcaseElement.appendChild(errorElement);
                            testsuiteElement.appendChild(testcaseElement);
                        }
                    } else {
						logDebugForDevice(
								log,
								idleDevice,
								colorize("Device " + device(idleDevice) + " is not waiting for any report, nor have any tests to execute.")
						);
                    }
                }

                // print progress at 0, 30 and 60 seconds and than each minute
                if (waitRound == 6 || (waitRound % 12 == 0)) {
                    logProgressReport(log, er, waitRound, executionsToDoFlight, executionsInFlight);
                }
                Thread.sleep(5000);
                waitRound++;
            } // end of while there is executing, or to be executed.
            this.logSummaryReport(log, er);

            testsuiteElement.setAttribute("errors", "" + er.getNbOfErrors());
            testsuiteElement.setAttribute("failures", "" + er.getNbOfTestFailures());
            long totalDuration = (System.currentTimeMillis() - startMillis) / 1000;
            log.info("Wall time of running reports is " + totalDuration + " seconds.");
            testsuiteElement.setAttribute("time", "" + totalDuration);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(reportFile);
            transformer.transform(source, result);
        } catch (JAXBException e) {
            log.error("could not parse the descriptor file " + descriptor, e);
        } catch (ParserConfigurationException e1) {
            log.error("could not build junit xml document", e1);
        } catch (TransformerException e) {
            log.error("could not build junit xml document", e);
        } catch (InterruptedException e) {
            log.info("the test suite has been interrupted", e);
        }
	}

	/**
	 * Logs (in debug level) given line, if given device has not logged exactly same message as last message.
	 *
	 * @see #lastDebugLogPerDevice
	 */
	private void logDebugForDevice(Log log, Device device, String message) {
		String lastLog = lastDebugLogPerDevice.get(device);
		if(!Objects.equals(lastLog, message)) {
			lastDebugLogPerDevice.put(device, message);
			log.debug(message);
		}
	}

    private void logProgressReport(Log log, ExecReport er, int waitRound, List<FutureExecution> executionsToDoFlight, List<Execution> executionsInFlight) {
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
                        "@|bold,underline " + er.getTotalExecutionsAtemps() + " done|@(" +
                        "@|" + ExecReport.COLOR_SUCCESS + " " + er.getNbOfSuccess() + "|@/" +
                        "@|" + ExecReport.COLOR_FAILURE + " " + er.getNbOfTestFailures() + "|@/" +
                        "@|" + ExecReport.COLOR_EXECERR + " " + er.getNbOfExecErrors() +"|@/" +
                        "@|" + ExecReport.COLOR_TIMEOUT + " " + er.getNbOfTimeouts() +"|@), " +
                        "@|bold " + executionsToDoFlight.size() + " waiting|@, " +
                        "@|italic,underline " + executionsInFlight.size() + " inProcess|@) " +
                        "I have waited about " + (waitRound * 5) + " seconds for reports till now. Going to wait another 5 seconds."
        ));
    }

	private void logSummaryReport(Log log, ExecReport execReport) {
		String headlineColor = "red";
		if(execReport.isWithoutNegativeResults()) {
			headlineColor = "green";
		} else {
			log.warn("There was some negative test results (" + execReport.getNbOfAllNegativeResults() + ")! See info report bellow for summary or have a look at log or test report file.");
		}
		log.info(colorize(
				"@|bold," + headlineColor + " SUMMARY REPORT|@\n" +
						"\t@|" + ExecReport.COLOR_SUCCESS + " " +
						"recordSuccess|@ : @|bold " + execReport.getNbOfSuccess() + "|@\t (number of test executions with successful execution and asserts)\n" +
						"\t@|" + ExecReport.COLOR_FAILURE + " " +
						"failure|@ : @|bold " + execReport.getNbOfTestFailures() + "|@\t (number of tests with report with some failure. Failed on test asserts or failure during test execution)\n" +
						"\t@|" + ExecReport.COLOR_EXECERR + " " +
						"exec err|@: @|bold " + execReport.getNbOfExecErrors() + "|@\t (number of executions failed. It counts executions of nonexistent tests, problems with authentication to jamo during test executions and so on)\n" +
						"\t@|" + ExecReport.COLOR_TIMEOUT + " " +
						"recordTimeout|@ : @|bold " + execReport.getNbOfTimeouts() + "|@\t (report not found within recordTimeout after test execution started)\n" +
						"@|bold " +
						"total|@   : @|bold " + execReport.getTotalExecutionsAtemps() + "|@"
		));
	}

	/**
	 * Get AND remove any future execution instance for given device.
	 */
	private Optional<FutureExecution> popAnotherTestForDevice(List<FutureExecution> futureExecutions, Device device) {
		// futureExecutions.stream().filter(e -> e.device.equals(device)).findAny();
		for(Iterator<FutureExecution> futureExecutionsIterator = futureExecutions.iterator(); futureExecutionsIterator.hasNext();) {
			FutureExecution fe = futureExecutionsIterator.next();
			if(fe.device.equals(device)) {
				futureExecutionsIterator.remove();
				return Optional.of(fe);
			}
		}
        return Optional.empty();
    }

    private URI getReportUri(String executionId, String accessToken, String url) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url + "/rest/integration/report/" + executionId);
		return builder.build().encode().toUri();
	}

	private Report getReport(String executionId, String accessToken, String url) {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.setMessageConverters(getMessageConverters());
		URI reportUri = getReportUri(executionId, accessToken, url);
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-AUTH-TOKEN", accessToken);
		HttpEntity<String> entity = new HttpEntity<String>("parameters", headers);
		ResponseEntity<Report> responseEntity = restTemplate.exchange(reportUri, HttpMethod.GET, entity, Report.class);
		Report report = responseEntity.getBody();
		return report;
	}

	private ResponseStringWrapper runTestCase(Device device, TestCase testCase, UserKeyAndToken auth, String url, Log log)
			throws MojoExecutionException {
		log.info(colorize("Going to execute :" + testCase(testCase) + " on device " + device(device)));
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.setMessageConverters(getMessageConverters());
		UriComponentsBuilder builder;
		url += "/rest/integration";
		if (testCase.getSpecification() != null) {
			if (StringUtils.isEmpty(device.getUdid())) {
				builder = UriComponentsBuilder.fromHttpUrl(url + "/runSpec");
			} else {
				builder = UriComponentsBuilder.fromHttpUrl(url + "/runSpec/udid");
			}
			builder.queryParam("specification", testCase.getSpecification());
		} else {
			if (StringUtils.isEmpty(device.getUdid())) {
				builder = UriComponentsBuilder.fromHttpUrl(url + "/run");
			} else {
				builder = UriComponentsBuilder.fromHttpUrl(url + "/run/udid");
			}
		}
		// index parameter does not have meaning anymore, so sending 0. see mail from 20190726
		builder.queryParam("testCase", testCase.getName()).queryParam("index", "0").queryParam("userKey", auth.userKey);
		if (StringUtils.isEmpty(device.getUdid())) {
			log.debug(colorize("running with device name " + device(device)));
			builder.queryParam("device", device.getName());
		} else {
			log.debug(colorize("running with uniqueDeviceConfiguration " + deviceUdid(device)));
			builder.queryParam("uniqueDeviceIdentification", device.getUdid());
		}
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-AUTH-TOKEN", auth.authToken);
		HttpEntity<String> entity = new HttpEntity<String>("parameters", headers);
		final URI urlFinal = builder.build().encode().toUri();
		try {
			log.debug("Going to execute test using uri: " + urlFinal.toURL());
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		ResponseEntity<ResponseStringWrapper> responseEntity = restTemplate.exchange(urlFinal, HttpMethod.GET, entity,
				ResponseStringWrapper.class);
		ResponseStringWrapper result = responseEntity.getBody();
		log.debug("success:" + result.isSuccess() + "; execution id is:" + result.getMessage());
		return result;
	}

	private UserKeyAndToken login(Credentials credentials, String url) throws MojoExecutionException {
		final Log log = getLog();
		List<String> result = new ArrayList<>();
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.setMessageConverters(getMessageConverters());
		UriComponentsBuilder builder = UriComponentsBuilder
				.fromHttpUrl(url + "/rest/login")
				.queryParam("j_username", credentials.getUsername())
				.queryParam("j_password", "__hereComesYourActualPasswordWhichHave_" + credentials.getPassword().length() + "_characters__")
				.queryParam("account", credentials.getAccount());
		log.debug("Login POST request will be like this: " + builder.build());
		log.debug("Going to replace dummy (for logging) password with actual one.");
		builder = builder
				.replaceQueryParam("j_password", credentials.getPassword());
		ResponseEntity<LoginStatus> response = restTemplate.postForEntity(builder.build().encode().toUri(), null, LoginStatus.class);
		log.debug("Login response is " + response);
		if(false == response.getStatusCode().is2xxSuccessful()) {
			log.error("Response from login has not 2XX status code! Response:" + response);
			throw new RuntimeException("Login failed. Response is " + response.getStatusCode() + ". See log for more info.");
		}
		if(false == response.getBody().isSuccess()) {
			log.error("Response from login HAS 2XX status code, despite request body states that success is FALSE! (you cn try to check account parameter, which is \"" + credentials.getAccount() + "\") Response:" + response);
			log.error("Header with key X-AUTH-TOKEN = " + response.getHeaders().get("X-AUTH-TOKEN"));
			throw new RuntimeException("Login failed. Response is " + response.getBody() + ". See log for more info.");
		}

		return new UserKeyAndToken(
				response.getBody().getUserKeyString(),
				response.getHeaders().get("X-AUTH-TOKEN").get(0)
		);
	}

	private static class UserKeyAndToken {
		public final String userKey;
		public final String authToken;
		private UserKeyAndToken(String userKey, String authToken) {
			this.userKey = userKey;
			this.authToken = authToken;
		}
	}

	private static List<HttpMessageConverter<?>> getMessageConverters() {
		List<HttpMessageConverter<?>> converters = new ArrayList<HttpMessageConverter<?>>();
		converters.add(new MappingJackson2HttpMessageConverter());
		return converters;
	}

    private class FutureExecution {
        protected Device device;
        protected TestCase testCase;

        public FutureExecution(Device device, TestCase testCase) {
            this.device = device;
            this.testCase = testCase;
        }

        public Device getDevice() {
            return device;
        }

        public TestCase getTestCase() {
            return testCase;
        }

        public void setDevice(Device device) {
            this.device = device;
        }

        public void setTestCase(TestCase testCase) {
            this.testCase = testCase;
        }
    }

    private class Execution extends FutureExecution {
        private String executionId;
		private long startTimeMillis;

		public Execution(String executionId, Device device, TestCase testCase) {
            super(device, testCase);
            this.executionId = executionId;
            this.startTimeMillis = System.currentTimeMillis();
        }

        public String getExecutionId() {
            return executionId;
        }

        public void setExecutionId(String executionId) {
            this.executionId = executionId;
        }

		public long getStartTimeMillis() {
			return this.startTimeMillis;
		}
	}

	/**
	 * Simple "counter holder" class. You should report all tests to it using one of four methods (according result of test).
	 * <ul>
	 *     <li>{@link #recordSuccess(Execution, Report)}</li>
	 *     <li>{@link #recordTimeout(Execution, long)}</li>
	 *     <li>{@link #recordExecError(Device, TestCase, ResponseStringWrapper)}</li>
	 *     <li>{@link #recordTestFailure(Execution, Report, String)}</li>
	 * </ul>
	 */
	private class ExecReport {
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
                            "executeRequestFinishedAt(our): " + new Date(execution.startTimeMillis) + "\n" +
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
                            "executeRequestFinishedAt(our): " + new Date(execution.startTimeMillis) + "\n" +
                            "currentTime(our): " + new Date() + "\n" +
                            "execution.getExecutionId: " + execution.getExecutionId() + "\n" +
                            "durationTillNowMs(calculated): " + durationTillNowMs + "\n" +
                            "execution.getTestCase().getTimeout(): " + execution.getTestCase().getTimeout() + " -> " + (60 * 1000 * execution.getTestCase().getTimeout()) + "\n"
            );
			nbOfTimeouts++;
		}

		public void recordExecError(Device device, TestCase testCase, ResponseStringWrapper response) {
			log.warn(colorize(
					"@|" + COLOR_EXECERR + " error while executing|@ test (" + device(device) + ";" + testCase(testCase) + ")"
			));
            log.debug(
                    "executing error debug info:\n" +
                            "currentTime(our): " + new Date() + "\n" +
                            "response:" + response
            );
			this.nbOfExecErrors++;
		}

		public void recordTestFailure(Execution execution, Report report, String linkToReport) {
			log.warn(colorize(
					"@|" + COLOR_FAILURE + " Failure test execution|@ test (" + device(execution.getDevice()) + ";" + testCase(execution.getTestCase()) + "+ reportLink: " + linkToReport +" )"
			));
            log.debug(
                    "Failure test debug info:\n" +
                            "executeRequestFinishedAt(our): " + new Date(execution.startTimeMillis) + "\n" +
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
	}
}
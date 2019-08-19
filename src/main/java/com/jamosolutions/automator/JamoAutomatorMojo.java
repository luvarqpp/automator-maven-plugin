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
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.jamosolutions.automator.help.ExecReport;
import com.jamosolutions.automator.help.Execution;
import com.jamosolutions.automator.help.FutureExecution;
import com.jamosolutions.automator.help.UserKeyAndToken;
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

import static com.jamosolutions.automator.help.Colorizer.*;
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
	private final Map<Device, String> lastInfoLogPerDevice = new HashMap<>();

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
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder = null;
		try {
			docBuilder = docFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			log.error("could not build junit xml document", e);
			throw new RuntimeException("Unexpected error = " + e.getMessage(), e);
		}
		Document doc = docBuilder.newDocument();
		Element testsuiteElement = doc.createElement("testsuite");
		doc.appendChild(testsuiteElement);
        ExecReport er = new ExecReport(log);
		long startMillis = System.currentTimeMillis();
		String testSuiteName = "";
		try {
            //parse the testsuite file
            JAXBContext jaxbContext = JAXBContext.newInstance(TestSuite.class);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
            TestSuite testSuite = (TestSuite) jaxbUnmarshaller.unmarshal(testSuiteFile);
            //build the xml test suite document
            //http://help.catchsoftware.com/display/ET/JUnit+Format
			testSuiteName = testSuite.getName();
			testsuiteElement.setAttribute("name", testSuiteName);
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
                    // lock of code for checking report availability
					{
						Report report;
						try {
							report = getReport(execution.getExecutionId(), loginResult.authToken, testSuite.getUrl());
						} catch(Exception ex) {
							logDebugForDevice(
									log,
									execution.getDevice(),
									colorize(
											"Device " + device(execution.getDevice()) + " have still running test " +
													"@|blue " + execution.getTestCase() + "|@ on it (no report found with id " +
													"@|blue " + execution.getExecutionId() + "|@). Going to wait."

									)
							);
							execution.errorGettingReport(ex);
							final int errorsWhileGettingReport = execution.getErrorsWhileGettingReport();
							if(errorsWhileGettingReport >= 5) {
								throw new RuntimeException("Error while getting report has occurred " + errorsWhileGettingReport + " times!", ex);
							}
							if(errorsWhileGettingReport >= 3) {
								log.warn(colorize(
										"There were @|bold,red " + errorsWhileGettingReport + "|@ " +
												"errors while getting report from jamo. @|bold,yellow Going to suspend all activities for 7 minutes!|@"
								));
								Thread.sleep(7 * 60 * 1000);
							}
							continue;
						}
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
							continue;
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
							devicesWithSingleExecutionOnIt.add(execution.getDevice());
						}
					}
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
                    }
                } // end of for iterator through executionsInFlight

                Set<Device> idleDevices = new HashSet<>(testSuite.getDevices());
                idleDevices.removeAll(devicesWithSingleExecutionOnIt);

                for (Device idleDevice: idleDevices) {
                    Optional<FutureExecution> newTest = popAnotherTestForDevice(executionsToDoFlight, idleDevice);
                    if(newTest.isPresent()) {
                        FutureExecution needToExecute = newTest.get();
                        log.info(colorize("Device " + device(idleDevice) + " have no awaiting report for now, going to execute " + testCase(needToExecute.getTestCase()) + " on it."));
                        // request test execution
                        final TestCase testCaseToExecute = needToExecute.getTestCase();
						final long requestStartTime = System.currentTimeMillis();
                        ResponseStringWrapper response;
                        try {
                        	response = runTestCase(idleDevice, testCaseToExecute, loginResult, testSuite.getUrl(), log);
						} catch(Exception ex) {
                            // TODO count errors in needToExecute and stop it after some number of exceptions.
							logInfoForDevice(
									log,
									needToExecute.getDevice(),
									colorize(
											"Failed to execute test case on device " + device(needToExecute.getDevice()) + ", test case " +
													"@|blue " + needToExecute.getTestCase() + "|@. Going to try next round."

									)
							);
							continue;
						}
                        if (response.isSuccess()) {
                            executionsInFlight.add(new Execution(response.getMessage(), idleDevice, testCaseToExecute, requestStartTime));
                        } else {
                            er.recordExecError(idleDevice, testCaseToExecute, response, requestStartTime);
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
                    er.logProgressReport(waitRound, executionsToDoFlight, executionsInFlight);
                }
                Thread.sleep(5000);
                waitRound++;
            } // end of while there is executing, or to be executed.
        } catch (JAXBException e) {
            log.error("could not parse the descriptor file " + descriptor, e);
        } catch (InterruptedException e) {
            log.info("the test suite has been interrupted", e);
        } finally {
			er.logSummaryReport();
			testsuiteElement.setAttribute("errors", "" + er.getNbOfErrors());
			testsuiteElement.setAttribute("failures", "" + er.getNbOfTestFailures());
			long totalDuration = (System.currentTimeMillis() - startMillis) / 1000;
			log.info("Wall time of running reports is " + totalDuration + " seconds.");
			testsuiteElement.setAttribute("time", "" + totalDuration);
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = null;
			try {
				transformer = transformerFactory.newTransformer();
			} catch (TransformerConfigurationException e) {
				log.error("could not build junit xml document", e);
				throw new RuntimeException("Unexpected exception " + e.getMessage(), e);
			}
			DOMSource source = new DOMSource(doc);
			File reportFile = new File(getReportDirectory(baseDir).getAbsolutePath(), "TEST-com.jamoautomator." + testSuiteName + ".xml");
			StreamResult result = new StreamResult(reportFile);
			try {
				transformer.transform(source, result);
			} catch (TransformerException e) {
				log.error("could not build junit xml document", e);
				throw new RuntimeException("Unexpected exception " + e.getMessage(), e);
			}
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
	private void logInfoForDevice(Log log, Device device, String message) {
		String lastLog = lastInfoLogPerDevice.get(device);
		if(!Objects.equals(lastLog, message)) {
			lastInfoLogPerDevice.put(device, message);
			log.info(message);
		}
	}

	/**
	 * Get AND remove any future execution instance for given device.
	 */
	private Optional<FutureExecution> popAnotherTestForDevice(List<FutureExecution> futureExecutions, Device device) {
		// futureExecutions.stream().filter(e -> e.device.equals(device)).findAny();
		for(Iterator<FutureExecution> futureExecutionsIterator = futureExecutions.iterator(); futureExecutionsIterator.hasNext();) {
			FutureExecution fe = futureExecutionsIterator.next();
			if(fe.getDevice().equals(device)) {
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

	private static List<HttpMessageConverter<?>> getMessageConverters() {
		List<HttpMessageConverter<?>> converters = new ArrayList<HttpMessageConverter<?>>();
		converters.add(new MappingJackson2HttpMessageConverter());
		return converters;
	}}
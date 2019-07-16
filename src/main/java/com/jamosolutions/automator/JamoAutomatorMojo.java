package com.jamosolutions.automator;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

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

	protected File getReportDirectory(File baseDirAbsolutePath) {
		File reportFolder = new File(baseDirAbsolutePath, "target/surefire-reports");
		if (!reportFolder.exists()) {
			getLog().info("creating report folder");
			reportFolder.mkdirs();
		}
		return new File(baseDirAbsolutePath, "target/surefire-reports");
	}

	public void execute() throws MojoExecutionException {
		if (descriptor == null) {
			descriptor = "testsuite";
		}
		final File baseDirAbsolutePath = this.project.getBasedir().getAbsoluteFile();
		final Log log = getLog();
		final File testSuiteFile = new File(baseDirAbsolutePath + "/src/main/resources", descriptor + ".xml");

		log.debug("Going to use suite with name \"" + descriptor + "\". Full path is \"" + testSuiteFile.getAbsolutePath() + "\".");
		if (false == testSuiteFile.exists()) {
			log.warn(
					"There was no testsuite file found. Set proper \"suite\" file (by default testsuite.xml). Currently looking for file here: \"" +
							testSuiteFile.getAbsolutePath() + "\". Going to do nothing."
			);
			return;
		}
		try {
			//parse the testsuite file
			JAXBContext jaxbContext = JAXBContext.newInstance(TestSuite.class);
			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
			TestSuite testSuite = (TestSuite) jaxbUnmarshaller.unmarshal(testSuiteFile);
			List<Execution> executions = new ArrayList<>();
			//build the xml test suite document
			//http://help.catchsoftware.com/display/ET/JUnit+Format
			File reportFile = new File(getReportDirectory(baseDirAbsolutePath).getAbsolutePath(), "TEST-com.jamoautomator." + testSuite.getName() + ".xml");
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			Document doc = docBuilder.newDocument();
			Element testsuiteElement = doc.createElement("testsuite");
			doc.appendChild(testsuiteElement);
			testsuiteElement.setAttribute("name", testSuite.getName());
			int nbOfErrors = 0;
			int nbOfFailures = 0;

			UserKeyAndToken loginResult = login(testSuite.getCredentials(), testSuite.getUrl());
			for (Device device : testSuite.getDevices()) {
				int idx = 0;
				for (TestCase testCase : device.getTestCases()) {
					ResponseStringWrapper response = runTestCase(device, testCase, loginResult, testSuite.getUrl(), idx);
					idx++;
					log.info("" + response.isSuccess());
					if (response.isSuccess()) {
						executions.add(new Execution(response.getMessage(), device, testCase));
					} else {
						nbOfErrors++;
						Element testcaseElement = doc.createElement("testcase");
						testcaseElement.setAttribute("time", "0");
						testcaseElement.setAttribute("name", testCase.getName());
						testcaseElement.setAttribute("classname", "com.jamosolutions." + testSuite.getName() + "." + device.getName());
						Element errorElement = doc.createElement("error");
						errorElement.setAttribute("message", response.getMessage());
						testcaseElement.appendChild(errorElement);
						testsuiteElement.appendChild(testcaseElement);
					}
				}
			}
			Date now = new Date();
			long startMillis = now.getTime();
			int waitRound = 0;
			while (executions.size() > 0) {
				for (Iterator<Execution> iterator = executions.iterator(); iterator.hasNext();) {
					Execution execution = iterator.next();
					if ((new Date().getTime()) - startMillis > execution.getTestCase().getTimeout() * 60 * 1000) {
						iterator.remove();
						nbOfErrors++;
						Element testcaseElement = doc.createElement("testcase");
						long duration = ((new Date().getTime()) - startMillis) / 1000;
						testcaseElement.setAttribute("time", "" + duration);
						testcaseElement.setAttribute("name", execution.getTestCase().getName());
						testcaseElement.setAttribute("classname", "com.jamosolutions." + testSuite.getName() + "." + execution.getDevice().getName());
						Element errorElement = doc.createElement("error");
						errorElement.setAttribute("message", "could not find any report within " + (duration / 60) + " minutes");
						testcaseElement.appendChild(errorElement);
						testsuiteElement.appendChild(testcaseElement);
					} else {
						Report report = getReport(execution.getExecutionId(), loginResult.authToken, testSuite.getUrl());
						if (report != null) {
							Element testcaseElement = doc.createElement("testcase");
							long duration = (report.getEndDate().getTime() - report.getCreationDate().getTime()) / 1000;
							testcaseElement.setAttribute("time", "" + duration);
							testcaseElement.setAttribute("name", execution.getTestCase().getName());
							testcaseElement.setAttribute("classname", "com.jamosolutions." + testSuite.getName() + "." + execution.getDevice().getName());
							if (report.getStatus() != 0) {
								nbOfFailures++;
								Element failureElement = doc.createElement("failure");
								failureElement.setAttribute("message", "The test case did not succeed.");
								Text errorMessageDetail = doc.createTextNode("For more detail click " + testSuite.getUrl() + "/index.html?reportDetail="
										+ report.getKeyString());
								failureElement.appendChild(errorMessageDetail);
								testcaseElement.appendChild(failureElement);
							}
							testsuiteElement.appendChild(testcaseElement);
							iterator.remove();
						}
					}
				}
				if (waitRound == 6 || (waitRound % 12 == 0)) {
					log.info("I have waited about " + (waitRound*5) + " seconds for reports till now. Going to wait another 5 seconds.");
				}
				log.debug("did not find report: will try again in 5 seconds...........");
				Thread.sleep(5000);
				waitRound++;
			}
			testsuiteElement.setAttribute("errors", "" + nbOfErrors);
			testsuiteElement.setAttribute("failures", "" + nbOfFailures);
			long duration = ((new Date().getTime()) - startMillis) / 1000;
			testsuiteElement.setAttribute("time", "" + duration);
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			DOMSource source = new DOMSource(doc);
			StreamResult result = new StreamResult(reportFile);
			transformer.transform(source, result);
		} catch (JAXBException _ex) {
			log.error("could not parse the descriptor file " + descriptor);
		} catch (ParserConfigurationException _ex) {
			log.error("could not build junit xml document");
		} catch (TransformerException _ex) {
			log.error("could not build junit xml document");
		} catch (InterruptedException ex) {
			log.info("the test suite has been interrupted", ex);
		}
	}

	private Report getReport(String executionId, String accessToken, String url) {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.setMessageConverters(getMessageConverters());
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url + "/rest/integration/report/" + executionId);
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-AUTH-TOKEN", accessToken);
		HttpEntity<String> entity = new HttpEntity<String>("parameters", headers);
		ResponseEntity<Report> responseEntity = restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.GET, entity, Report.class);
		Report report = responseEntity.getBody();
		return report;
	}

	private ResponseStringWrapper runTestCase(Device device, TestCase testCase, UserKeyAndToken auth, String url, int index)
			throws MojoExecutionException {
		getLog().info("Running :" + testCase.getName() + " on device " + device.getName());
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
		builder.queryParam("testCase", testCase.getName()).queryParam("index", "" + index).queryParam("userKey", auth.userKey);
		if (StringUtils.isEmpty(device.getUdid())) {
			getLog().info("running with device name " + device.getName());
			builder.queryParam("device", device.getName());
		} else {
			getLog().info("running with uniqueDeviceConfiguration " + device.getUdid());
			builder.queryParam("uniqueDeviceIdentification", device.getUdid());
		}
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-AUTH-TOKEN", auth.authToken);
		HttpEntity<String> entity = new HttpEntity<String>("parameters", headers);
		ResponseEntity<ResponseStringWrapper> responseEntity = restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.GET, entity,
				ResponseStringWrapper.class);
		ResponseStringWrapper result = responseEntity.getBody();
		getLog().info("success:" + result.isSuccess());
		getLog().info("execution id is:" + result.getMessage());
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
			log.error("Response from login HAS 2XX status code, despite request body states that success is FALSE! Response:" + response);
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

	private class Execution {
		private String executionId;
		private Device device;
		private TestCase testCase;

		public Execution(String executionId, Device device, TestCase testCase) {
			super();
			this.executionId = executionId;
			this.device = device;
			this.testCase = testCase;
		}

		public String getExecutionId() {
			return executionId;
		}

		public void setExecutionId(String executionId) {
			this.executionId = executionId;
		}

		public Device getDevice() {
			return device;
		}

		public void setDevice(Device device) {
			this.device = device;
		}

		public TestCase getTestCase() {
			return testCase;
		}

		public void setTestCase(TestCase testCase) {
			this.testCase = testCase;
		}
	}
}
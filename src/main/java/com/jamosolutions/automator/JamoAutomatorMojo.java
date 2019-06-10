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
			System.out.println(testSuite.getDevices().get(0).getUdid());
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
			Integer nbOfErrors = 0;
			Integer nbOfFailures = 0;
			List<String> loginResult = login(testSuite.getCredentials(), testSuite.getUrl());
			String userKey = loginResult.get(0);
			String accessToken = loginResult.get(1);
			for (Device device : testSuite.getDevices()) {
				int idx = 0;
				for (TestCase testCase : device.getTestCases()) {
					ResponseStringWrapper response = runTestCase(device, testCase, userKey, accessToken, testSuite.getUrl(), idx);
					idx++;
					log.info("" + response.isSuccess());
					if (response.isSuccess()) {
						executions.add(new Execution(response.getMessage(), device, testCase));
					} else {
						nbOfErrors++;
						Element testcaseElement = doc.createElement("testcase");
						testcaseElement.setAttribute("time", "" + 0);
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
						Report report = getReport(execution.getExecutionId(), accessToken, testSuite.getUrl());
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
				log.info("did not find report: will try again in 5 seconds...........");
				Thread.sleep(5000);
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
		} catch (JAXBException e) {
			log.error("could not parse the descriptor file " + descriptor);
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ParserConfigurationException e1) {
			log.error("could not build junit xml document");
		} catch (TransformerException e) {
			log.error("could not build junit xml document");
		} catch (InterruptedException e) {
			log.info("the test suite has been interrupted");
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

	private ResponseStringWrapper runTestCase(Device device, TestCase testCase, String userKey, String accessToken, String url, int index)
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
		builder.queryParam("testCase", testCase.getName()).queryParam("index", "" + index).queryParam("userKey", userKey);
		if (StringUtils.isEmpty(device.getUdid())) {
			getLog().info("running with device name " + device.getName());
			builder.queryParam("device", device.getName());
		} else {
			getLog().info("running with uniqueDeviceConfiguration " + device.getUdid());
			builder.queryParam("uniqueDeviceIdentification", device.getUdid());
		}
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-AUTH-TOKEN", accessToken);
		HttpEntity<String> entity = new HttpEntity<String>("parameters", headers);
		ResponseEntity<ResponseStringWrapper> responseEntity = restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.GET, entity,
				ResponseStringWrapper.class);
		ResponseStringWrapper result = responseEntity.getBody();
		getLog().info("success:" + result.isSuccess());
		getLog().info("execution id is:" + result.getMessage());
		return result;
	}

	private List<String> login(Credentials credentials, String url) throws MojoExecutionException {
		List<String> result = new ArrayList<>();
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.setMessageConverters(getMessageConverters());
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url + "/rest/login").queryParam("j_username", credentials.getUsername())
				.queryParam("j_password", credentials.getPassword()).queryParam("account", credentials.getAccount());
		HttpEntity<LoginStatus> response = restTemplate.postForEntity(builder.build().encode().toUri(), null, LoginStatus.class);
		LoginStatus loginStatus = response.getBody();
		HttpHeaders headers = response.getHeaders();
		result.add(loginStatus.getUserKeyString());
		result.add(headers.get("X-AUTH-TOKEN").get(0));
		return result;
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
package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Credentials;
import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.ResponseStringWrapper;
import com.jamosolutions.automator.domain.TestCase;
import com.jamosolutions.jamoAutomator.domain.Report;
import org.apache.maven.plugin.logging.Log;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.MalformedURLException;
import java.net.URI;

import static com.jamosolutions.automator.help.Colorizer.*;

/**
 * {@link JamoAutomatorClient} class is meant to manage login/session to jamo automator rest api and to provide convient
 * methods for communication to api. For api documentation you can have a look at
 * https://app.swaggerhub.com/apis/jamo/jamo-automator-api/1.0
 */
public class JamoAutomatorClient {
    private final Log log;
    private final String url;
    private final JamoAutomatorLoginManager loginManager;

    public JamoAutomatorClient(Log log, Credentials credentials, String url) {
        this.log = log;
        this.url = url;
        this.loginManager = new JamoAutomatorLoginManager(log, credentials, url);
    }

    public ResponseStringWrapper runTestCase(Device device, TestCase testCase) {
        // TODO add try/catch for I/O exception and retry after few seconds.
        return runTestCaseBare(device, testCase);
    }

    private ResponseStringWrapper runTestCaseBare(Device device, TestCase testCase) {
        log.info(colorize("Going to execute :" + testCase(testCase) + " on device " + device(device)));
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(Converters.JACKSON_TO_HTTP);
        UriComponentsBuilder builder;
        final String urlForRequest = this.url + "/rest/integration";
        if (testCase.getSpecification() != null) {
            if (StringUtils.isEmpty(device.getUdid())) {
                builder = UriComponentsBuilder.fromHttpUrl(urlForRequest + "/runSpec");
            } else {
                builder = UriComponentsBuilder.fromHttpUrl(urlForRequest + "/runSpec/udid");
            }
            builder.queryParam("specification", testCase.getSpecification());
        } else {
            if (StringUtils.isEmpty(device.getUdid())) {
                builder = UriComponentsBuilder.fromHttpUrl(urlForRequest + "/run");
            } else {
                builder = UriComponentsBuilder.fromHttpUrl(urlForRequest + "/run/udid");
            }
        }
        // index parameter does not have meaning anymore, so sending 0. see mail from 20190726
        builder.queryParam("testCase", testCase.getName()).queryParam("index", "0").queryParam("userKey", this.loginManager.getUserKey());
        if (StringUtils.isEmpty(device.getUdid())) {
            log.debug(colorize("running with device name " + device(device)));
            builder.queryParam("device", device.getName());
        } else {
            log.debug(colorize("running with uniqueDeviceConfiguration " + deviceUdid(device)));
            builder.queryParam("uniqueDeviceIdentification", device.getUdid());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-AUTH-TOKEN", this.loginManager.getAuthToken());
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

    // used also for xml report
    public URI getReportUri(String executionId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(this.url + "/rest/integration/report/" + executionId);
        return builder.build().encode().toUri();
    }

    public Report getReport(String executionId) {
        // TODO add try/catch for I/O exception and retry after few seconds.
        return getReportBare(executionId);
    }

    private Report getReportBare(String executionId) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(Converters.JACKSON_TO_HTTP);
        final String authToken = this.loginManager.getAuthToken();
        URI reportUri = getReportUri(executionId);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-AUTH-TOKEN", authToken);
        HttpEntity<String> entity = new HttpEntity<String>("parameters", headers);
        ResponseEntity<Report> responseEntity = restTemplate.exchange(reportUri, HttpMethod.GET, entity, Report.class);
        Report report = responseEntity.getBody();
        report.setBaseUrl(this.url);
        return report;
    }
}

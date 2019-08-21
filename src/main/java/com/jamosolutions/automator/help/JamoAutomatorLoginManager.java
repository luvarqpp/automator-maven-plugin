package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Credentials;
import com.jamosolutions.jamoAutomator.domain.LoginStatus;
import org.apache.maven.plugin.logging.Log;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link JamoAutomatorLoginManager} class is meant to manage login/session to jamo automator rest api. For api
 * documentation you can have a look at
 * https://app.swaggerhub.com/apis/jamo/jamo-automator-api/1.0
 * <p>
 * If you get an exception due token expiration, you should call {@link #refreshAndGetLoginResult()} method to do login
 * again.
 */
public class JamoAutomatorLoginManager {
    private final Log log;
    private final Credentials credentials;
    private final String url;

    private UserKeyAndToken loginResultCached = null;

    public JamoAutomatorLoginManager(Log log, Credentials credentials, String url) {
        this.log = log;
        this.credentials = credentials;
        this.url = url;
    }

    /**
     * Method will return cached data. If no data was cached prior its call, it will do login by using
     * {@link #refreshAndGetLoginResult()} method.
     *
     * @return authToken from last login. If no login has been done yet, it will do one and cache its response
     */
    public String getAuthToken() {
        if (this.loginResultCached == null) {
            this.refreshAndGetLoginResult();
        }
        return this.loginResultCached.authToken;
    }

    public String getUserKey() {
        if (this.loginResultCached == null) {
            this.refreshAndGetLoginResult();
        }
        return this.loginResultCached.userKey;
    }

    /**
     * Force refresh auth token by doing login call. Obtained {@link UserKeyAndToken} instance is saved
     * in {@link #loginResultCached} attribute and used by {@link #getAuthToken()} method.
     *
     * @return obtained {@link UserKeyAndToken} data, or throws an exception if something goes wrong
     */
    public UserKeyAndToken refreshAndGetLoginResult() {
        UserKeyAndToken loginResult = login(this.credentials, this.url);
        this.loginResultCached = loginResult;
        return this.loginResultCached;
    }

    // TODO make both exception typed and document them
    private UserKeyAndToken login(Credentials credentials, String url) {
        List<String> result = new ArrayList<>();
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(Converters.JACKSON_TO_HTTP);
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
        if (false == response.getStatusCode().is2xxSuccessful()) {
            log.error("Response from login has not 2XX status code! Response:" + response);
            throw new RuntimeException("Login failed. Response is " + response.getStatusCode() + ". See log for more info.");
        }
        if (false == response.getBody().isSuccess()) {
            log.error("Response from login HAS 2XX status code, despite request body states that success is FALSE! (you cn try to check account parameter, which is \"" + credentials.getAccount() + "\") Response:" + response);
            log.error("Header with key X-AUTH-TOKEN = " + response.getHeaders().get("X-AUTH-TOKEN"));
            throw new RuntimeException("Login failed. Response is " + response.getBody() + ". See log for more info.");
        }

        return new UserKeyAndToken(
                response.getBody().getUserKeyString(),
                response.getHeaders().get("X-AUTH-TOKEN").get(0)
        );
    }
}

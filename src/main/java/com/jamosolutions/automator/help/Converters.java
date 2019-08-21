package com.jamosolutions.automator.help;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.Collections;
import java.util.List;

public class Converters {
    public static List<HttpMessageConverter<?>> JACKSON_TO_HTTP = Collections.singletonList(new MappingJackson2HttpMessageConverter());
}

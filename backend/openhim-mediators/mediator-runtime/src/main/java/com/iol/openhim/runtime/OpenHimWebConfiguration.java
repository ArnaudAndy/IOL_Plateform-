package com.iol.openhim.runtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Teaches Spring MVC the media type used by the OpenHIM mediator contract.
 *
 * {@code application/json+openhim} is not a structured-suffix JSON type (that
 * would end with {@code +json}), so Spring's Jackson converter does not select
 * it automatically. Without this explicit registration, a valid mediator
 * envelope is replaced by an HTTP 500 before it reaches OpenHIM.
 */
@Configuration
public class OpenHimWebConfiguration implements WebMvcConfigurer {

    public static final MediaType OPENHIM_JSON =
            MediaType.parseMediaType("application/json+openhim");

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> {
                    List<MediaType> supported =
                            new ArrayList<>(converter.getSupportedMediaTypes());
                    if (!supported.contains(OPENHIM_JSON)) {
                        supported.add(OPENHIM_JSON);
                        converter.setSupportedMediaTypes(supported);
                    }
                });
    }
}

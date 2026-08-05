package com.iol.openhim.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenHimWebConfigurationTest {

    @Test
    void jacksonCanSerializeMediatorEnvelopeWithOpenHimMediaType() {
        MappingJackson2HttpMessageConverter jackson =
                new MappingJackson2HttpMessageConverter();
        List<HttpMessageConverter<?>> converters =
                new ArrayList<>(List.of(jackson));

        assertThat(jackson.canWrite(Map.class, OpenHimWebConfiguration.OPENHIM_JSON))
                .isFalse();

        new OpenHimWebConfiguration().extendMessageConverters(converters);

        assertThat(jackson.canWrite(Map.class, OpenHimWebConfiguration.OPENHIM_JSON))
                .isTrue();
    }
}

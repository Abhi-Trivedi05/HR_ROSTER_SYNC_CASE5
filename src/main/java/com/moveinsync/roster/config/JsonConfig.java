package com.moveinsync.roster.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class JsonConfig {
    @Bean Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder->builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .postConfigurer(mapper->mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(65536).maxNestingDepth(20).build()));
    }
}

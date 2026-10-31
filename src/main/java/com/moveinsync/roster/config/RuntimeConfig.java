package com.moveinsync.roster.config;

import com.moveinsync.roster.shared.FailureProbe;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableScheduling
public class RuntimeConfig {
    @Bean @ConditionalOnMissingBean FailureProbe failureProbe() { return point -> { }; }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}

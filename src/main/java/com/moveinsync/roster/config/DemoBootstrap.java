package com.moveinsync.roster.config;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration @Profile("demo")
public class DemoBootstrap {
    @Bean ApplicationRunner demoUsers(JdbcTemplate jdbc,PasswordEncoder passwords,@Value("${DEMO_PASSWORD:LocalDemoOnly!2026}") String password) {
        return args->{
            UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
            UUID employee=UUID.fromString("22222222-2222-4222-8222-222222222222");
            jdbc.update("insert into tenants(id,name) values (?,'Demo tenant') on conflict do nothing",tenant);
            jdbc.update("insert into offices(tenant_id,code,name) values (?,'BLR','Demo office') on conflict do nothing",tenant);
            jdbc.update("insert into employees(id,tenant_id,external_employee_id,name,email,office_code,created_at,updated_at,hr_hash) values (?,?,'DEMO-EMPLOYEE','Demo','demo@example.invalid','BLR',now(),now(),roster_hr_hash('Demo','demo@example.invalid',null,null,'BLR',true)) on conflict do nothing",employee,tenant);
            for (String role:List.of("EMPLOYEE","ADMIN","OPERATOR","HR_INTEGRATION")) {
                String username=role.equals("HR_INTEGRATION")?"hr":role.toLowerCase(Locale.ROOT);
                jdbc.update("insert into app_users(tenant_id,id,username,password_hash,role,employee_id) values (?,?,?,?,?,?) on conflict(tenant_id,username) do nothing",
                        tenant,UUID.randomUUID(),username,passwords.encode(password),role,role.equals("EMPLOYEE")?employee:null);
            }
        };
    }
}

package com.moveinsync.roster;

import com.moveinsync.roster.ingestion.*;
import com.moveinsync.roster.outbox.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"security.local.enabled=true","roster.cache.enabled=false","roster.outbox.enabled=false","roster.booking.consumer-enabled=false","roster.snapshot.recovery-enabled=false","spring.datasource.hikari.data-source-properties.socketTimeout=3","spring.datasource.hikari.data-source-properties.cancelSignalTimeout=1"})
@Testcontainers @org.springframework.test.annotation.DirtiesContext
class InfrastructureFailureIT {
    static final Network NETWORK=Network.newNetwork();
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine").withNetwork(NETWORK).withNetworkAliases("pg");
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer("rabbitmq:4-management-alpine").withNetwork(NETWORK).withNetworkAliases("rabbit");
    @Container static final ToxiproxyContainer PROXY=new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(NETWORK);
    static ToxiproxyContainer.ContainerProxy db,broker;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        db=PROXY.getProxy(PG,5432);broker=PROXY.getProxy(RABBIT,5672);
        registry.add("spring.datasource.url",()->"jdbc:postgresql://"+PROXY.getHost()+":"+db.getProxyPort()+"/"+PG.getDatabaseName());
        registry.add("spring.datasource.username",PG::getUsername);registry.add("spring.datasource.password",PG::getPassword);
        registry.add("spring.rabbitmq.host",PROXY::getHost);registry.add("spring.rabbitmq.port",broker::getProxyPort);
        registry.add("spring.rabbitmq.username",RABBIT::getAdminUsername);registry.add("spring.rabbitmq.password",RABBIT::getAdminPassword);
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EventProcessor processor;
    @Autowired RabbitChangePublisher publisher;
    @Test void databaseConnectionFailureCannotPartiallyApplyEvent() throws Exception {
        UUID tenant=UUID.randomUUID();jdbc.update("insert into tenants(id,name) values (?,'Fixture')",tenant);
        jdbc.update("insert into offices(tenant_id,code,name) values (?,'BLR','Fixture')",tenant);
        var event=new HrEvent(UUID.randomUUID(),"FAIL",1L,HrEvent.Type.CREATE,Instant.now(),new HrEvent.Data("Fixture","f@example.invalid",null,null,"BLR",true));
        db.setConnectionCut(true);
        try { assertThatThrownBy(()->processor.process(tenant,event,0)).isInstanceOf(RuntimeException.class); }
        finally { db.setConnectionCut(false); }
        assertThat(jdbc.queryForObject("select count(*) from employees where tenant_id=?",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox where tenant_id=?",Long.class,tenant)).isZero();
    }
    @Test void brokerUnavailableFailsPublicationInsteadOfAcknowledging() throws Exception {
        broker.setConnectionCut(true);
        try { assertThatThrownBy(()->publisher.publish(new ChangeMessage(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"EMPLOYEE_UPDATED",1,Instant.now()))).isInstanceOf(Exception.class); }
        finally { broker.setConnectionCut(false); }
    }
}

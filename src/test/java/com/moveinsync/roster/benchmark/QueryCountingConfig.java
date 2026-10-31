package com.moveinsync.roster.benchmark;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.AbstractDataSource;

@TestConfiguration(proxyBeanMethods=false)
public class QueryCountingConfig {
    public static final AtomicLong OPERATIONS=new AtomicLong();
    @Bean static BeanPostProcessor countJdbc() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if (!(bean instanceof DataSource source)||!name.equals("dataSource")) return bean;
                return new AbstractDataSource() {
                    @Override public Connection getConnection() throws SQLException { return wrap(source.getConnection()); }
                    @Override public Connection getConnection(String user,String password) throws SQLException { return wrap(source.getConnection(user,password)); }
                };
            }
        };
    }
    static Connection wrap(Connection connection) {
        return (Connection)Proxy.newProxyInstance(QueryCountingConfig.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)-> {
            Object value=invoke(connection,method,args);
            if (!(value instanceof Statement statement)) return value;
            Class<?> type=statement instanceof CallableStatement?CallableStatement.class:statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            return Proxy.newProxyInstance(QueryCountingConfig.class.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {
                if (m.getName().startsWith("execute")) OPERATIONS.incrementAndGet();
                return invoke(statement,m,a);
            });
        });
    }
    static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
    }
}

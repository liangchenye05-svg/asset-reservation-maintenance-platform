package com.example.equipmentmanagement.testsupport;

import java.lang.annotation.*;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/** Applies both to Maven and direct IDEA/JUnit launches. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ActiveProfiles(value="isolated-test", inheritProfiles=false)
@ContextConfiguration(initializers=IsolatedDatabaseTest.Initializer.class)
public @interface IsolatedDatabaseTest {
    class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            // Resolve the final dynamic properties before datasource/Flyway beans are instantiated.
            context.addBeanFactoryPostProcessor(factory -> TestDatabase.verifyEnvironment(context.getEnvironment()));
        }
    }
}

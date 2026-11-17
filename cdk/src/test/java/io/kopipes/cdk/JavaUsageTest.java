package io.kopipes.cdk;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.lambda.Code;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A stack written in Java, as the urlshortener example's are: the constructs take their defaults without Kotlin's
 * named arguments ({@code @JvmOverloads}).
 */
class JavaUsageTest {

    private static Code code() throws Exception {
        Path dir = Files.createTempDirectory("kopipes-cdk-java-test");
        Files.writeString(dir.resolve("handler.txt"), "placeholder");
        return Code.fromAsset(dir.toString());
    }

    @Test
    void aControlServiceInJava() throws Exception {
        // Arrange
        KopipesStack stack = new KopipesStack(new App(),
                new ServiceProps("myorg", "urls", "urls-control-service", Stage.LOCAL, "us-east-1"));
        Code jar = code();

        // Act
        EventsTable events = new EventsTable(stack, "Events");
        new KopipesFunction(stack, "listener", new KopipesFunctionProps(jar, "org.myorg.urls.Listener::handleRequest"))
                .listenTo()
                .uses(events);
        new KopipesFunction(stack, "trigger", new KopipesFunctionProps(jar, "org.myorg.urls.Trigger::handleRequest"))
                .triggeredBy(events)
                .uses(events);

        // Assert
        Template template = Template.fromStack(stack);
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "FunctionName", "urls-control-service-local-listener",
                "Environment", Map.of("Variables", Match.objectLike(Map.of(
                        EnvVars.EVENT_TABLE_NAME, Match.anyValue(),
                        EnvVars.BUS_NAME, "urls-event-hub-local-bus")))));
        template.resourceCountIs("AWS::Lambda::EventSourceMapping", 2);
    }

    @Test
    void anEventHubInJava() {
        // Arrange
        KopipesStack stack = new KopipesStack(new App(),
                new ServiceProps("myorg", "urls", "urls-event-hub", Stage.LOCAL, "us-east-1"));

        // Act
        new EventHub(stack, "Hub");

        // Assert
        Template template = Template.fromStack(stack);
        template.hasResourceProperties("AWS::Events::EventBus", Map.of("Name", "urls-event-hub-local-bus"));
        template.hasResourceProperties("AWS::Kinesis::Stream", Map.of("Name", "urls-event-hub-local-s1"));
    }
}

package io.camunda.demo.hospital.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The bundle folder as the tests see it: the parent folder of this Java project, which holds the
 * BPMN model and the Camunda forms next to it.
 *
 * <p>The tests read the very files that get deployed, so there is no second copy that could go
 * stale and no file name to keep in sync. The files are looked up instead of hard-coded, because of
 * how Camunda Modeler deploys: the folder is a <em>process application</em> (it contains the
 * {@code .process-application} marker), and pressing Deploy sends <em>every</em> {@code .bpmn} and
 * {@code .form} below that folder - including the ones in sub folders such as {@code _analysis/}.
 * One unrepaired model or one stale copy in the tree makes the whole deployment fail, so the
 * lookups here walk the whole tree and refuse to guess.
 */
public final class Bundle {

    private Bundle() {
    }

    private static final Path FOLDER = Path.of("..").toAbsolutePath().normalize();

    /** The bundle folder (the parent of the java project). */
    public static Path folder() {
        return FOLDER;
    }

    /**
     * The one BPMN model of the folder tree.
     *
     * @throws AssertionError when the tree holds no model or more than one
     */
    public static Path model() {
        List<Path> models = filesWithSuffix(".bpmn");
        assertThat(models)
                .as("the folder tree of %s must contain exactly one .bpmn model: the folder is a Camunda "
                        + "Modeler process application, so Deploy sends every .bpmn below it and two models "
                        + "with the same process id are rejected ('Duplicated process id in resources')", FOLDER)
                .hasSize(1);
        return models.get(0);
    }

    /** File name of the model, as it has to be deployed. */
    public static String modelName() {
        return model().getFileName().toString();
    }

    /** All Camunda forms of the folder tree, sorted by path - the forms a deployment carries. */
    public static List<Path> forms() {
        return filesWithSuffix(".form");
    }

    /** The {@code id} a form file declares for itself. */
    public static String formId(Path form) {
        try {
            JsonNode root = new ObjectMapper().readTree(form.toFile());
            return root.path("id").asText();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the form " + form, e);
        }
    }

    /** Path relative to the bundle folder, for readable test failures. */
    public static String relative(Path file) {
        return FOLDER.relativize(file).toString();
    }

    private static List<Path> filesWithSuffix(String suffix) {
        try (Stream<Path> entries = Files.walk(FOLDER)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk the bundle folder " + FOLDER, e);
        }
    }
}

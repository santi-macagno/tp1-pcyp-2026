package ar.edu.unc.fcefyn.pcp.tp1.solution.invariants;

import ar.edu.unc.fcefyn.pcp.tp1.api.ConfigurationLoader;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;
import ar.edu.unc.fcefyn.pcp.tp1.solution.ConcurrentSimulation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Fase 6: corre la simulacion con distintas configs y chequea los invariantes con InvariantChecker.
// Cada escenario se repite varias veces porque un error de sincronizacion puede salir
// solo en algunas corridas. Si algo se cuelga (deadlock), el timeout lo corta.
class InvariantsTest {

    // cuantas veces corre cada escenario
    private static final int REPETITIONS = 20;

    // sin demoras tarda menos de un segundo, si pasa los 10 es que se trabo
    private static final Duration TIMEOUT = Duration.ofSeconds(100);

    @TempDir
    Path outputDirectory;

    // delays en ms: asignacion, validacion, impresion, calidad
    record Scenario(String name, int orders, int rows, int columns,
                    int assignmentThreads, int validationThreads, int printingThreads, int qualityThreads,
                    int validPct, int printPct, int qualityPct, long[] delays, long seed) {

        SimulationConfig toConfig(Path output) {
            return new SimulationConfig(orders, rows, columns,
                    assignmentThreads, validationThreads, printingThreads, qualityThreads,
                    validPct, printPct, qualityPct,
                    delays[0], delays[1], delays[2], delays[3], seed, output);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final long[] NO_DELAY = {0, 0, 0, 0};

    static Stream<Scenario> scenarios() {
        return Stream.of(
                // la oficial pero sin demoras
                new Scenario("oficial sin demoras", 500, 10, 20, 3, 2, 3, 2, 85, 90, 95, NO_DELAY, 20261001L),
                // una sola impresora: todos se pelean por la misma y tienen que esperar
                new Scenario("una impresora", 30, 1, 1, 3, 2, 3, 2, 100, 100, 100, NO_DELAY, 7L),
                // pocas impresoras y muchos hilos, para forzar que compitan
                new Scenario("pocas impresoras, muchos hilos", 300, 2, 3, 8, 8, 8, 8, 85, 100, 95, NO_DELAY, 11L),
                // un hilo por etapa, tiene que avanzar igual
                new Scenario("un hilo por etapa", 200, 10, 20, 1, 1, 1, 1, 85, 90, 95, NO_DELAY, 13L),
                // se rechazan todas: impresion y calidad no reciben nada pero tienen que terminar
                new Scenario("todo rechazado", 100, 2, 2, 3, 2, 3, 2, 0, 90, 95, NO_DELAY, 17L),
                // fallan todas las impresiones, cada orden rompe una impresora
                new Scenario("todas las impresiones fallan", 40, 6, 10, 3, 2, 3, 2, 100, 0, 95, NO_DELAY, 19L),
                // demoras chicas para que los hilos se mezclen distinto
                new Scenario("demoras chicas", 60, 4, 5, 3, 2, 3, 2, 85, 90, 95, new long[]{1, 2, 3, 1}, 23L)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void invariantsHoldOnEveryRun(Scenario scenario) {
        SimulationConfig config = scenario.toConfig(outputDirectory);
        for (int run = 1; run <= REPETITIONS; run++) {
            runAndVerify(config, scenario.name() + ", corrida " + run);
        }
    }

    // la config de tp1.properties, con demoras en 0 para que no tarde 30 segundos
    @Test
    void officialConfigurationWithoutDelays() throws Exception {
        SimulationConfig official = ConfigurationLoader.load(Path.of("config", "tp1.properties"));
        SimulationConfig config = new SimulationConfig(
                official.totalOrders(), official.printerRows(), official.printerColumns(),
                official.assignmentThreads(), official.validationThreads(),
                official.printingThreads(), official.qualityControlThreads(),
                official.validModelPercentage(), official.printSuccessPercentage(),
                official.qualityApprovedPercentage(),
                0, 0, 0, 0, official.randomSeed(), outputDirectory);
        runAndVerify(config, "config/tp1.properties sin demoras");
    }

    private void runAndVerify(SimulationConfig config, String label) {
        SimulationResult result = assertTimeoutPreemptively(TIMEOUT,
                () -> new ConcurrentSimulation().execute(config),
                () -> label + ": la simulacion no termino a tiempo (posible deadlock)");
        List<String> violations = InvariantChecker.check(config, result);
        assertTrue(violations.isEmpty(),
                () -> label + ": se violaron invariantes:\n  - " + String.join("\n  - ", violations));
    }
}

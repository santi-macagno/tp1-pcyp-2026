package ar.edu.unc.fcefyn.pcp.tp1.solution.invariants;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterState;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

// Fase 6: chequea los 10 invariantes del enunciado sobre una corrida de la simulacion.
// Solo mira lo que devuelve execute() y los archivos de salida, no usa ninguna clase
// nuestra (workers, colas, farm). Por eso sirve igual para la version secuencial y la concurrente.
//
// La idea es "volver a jugar" eventos.csv: vamos leyendo los eventos en orden y llevamos
// en que estado esta cada orden y que orden tiene cada impresora. Si un evento no tiene
// sentido con lo que paso antes, es un error.
//
// No cortamos en el primer error: los juntamos y el test los muestra juntos.
public final class InvariantChecker {

    static final String EVENT_HEADER =
            "sequence;elapsedMs;thread;orderId;stage;event;fromState;toState;printer";
    static final String ORDER_HEADER =
            "orderId;finalState;printer;assignmentCount;validationCount;printingCount;qualityControlCount";

    // tope de errores que guardamos, sino el mensaje del test queda enorme
    private static final int MAX_VIOLATIONS = 30;

    // transiciones validas del enunciado y que etapa tiene que hacer cada una
    private static final Map<OrderState, Map<OrderState, String>> ALLOWED = new EnumMap<>(OrderState.class);

    static {
        allow(OrderState.CREATED, OrderState.WAITING_VALIDATION, "ASSIGNMENT");
        allow(OrderState.WAITING_VALIDATION, OrderState.READY_TO_PRINT, "VALIDATION");
        allow(OrderState.WAITING_VALIDATION, OrderState.REJECTED, "VALIDATION");
        allow(OrderState.READY_TO_PRINT, OrderState.PRINTED, "PRINTING");
        allow(OrderState.READY_TO_PRINT, OrderState.PRINT_FAILED, "PRINTING");
        allow(OrderState.PRINTED, OrderState.APPROVED, "QUALITY_CONTROL");
        allow(OrderState.PRINTED, OrderState.DEFECTIVE, "QUALITY_CONTROL");
    }

    private static void allow(OrderState from, OrderState to, String stage) {
        ALLOWED.computeIfAbsent(from, key -> new EnumMap<>(OrderState.class)).put(to, stage);
    }

    private final SimulationConfig config;
    private final SimulationResult result;
    private final List<String> violations = new ArrayList<>();

    private InvariantChecker(SimulationConfig config, SimulationResult result) {
        this.config = config;
        this.result = result;
    }

    // Devuelve la lista de errores encontrados (vacia = esta bien).
    // Los archivos los lee de config.outputDirectory().
    public static List<String> check(SimulationConfig config, SimulationResult result) {
        InvariantChecker checker = new InvariantChecker(config, result);
        checker.run();
        return checker.violations;
    }

    private void run() {
        if (result == null) {
            fail("execute() devolvio null");
            return;
        }
        Map<Integer, OrderState> expected = expectedFinalStates();
        checkResult(expected);
        try {
            Map<String, Integer> assignmentsPerPrinter = checkEvents(expected);
            checkPrinterUsage(assignmentsPerPrinter);
            checkOrdersFile();
            checkSummaryFile();
        } catch (IOException exception) {
            fail("No se pudo leer un archivo de salida: " + exception.getMessage());
        }
    }

    // ---- lo que devuelve execute() ----

    // Como OutcomeDecider es determinista, sabemos de antemano como tiene que terminar cada orden
    private Map<Integer, OrderState> expectedFinalStates() {
        Map<Integer, OrderState> expected = new HashMap<>();
        for (int id = 1; id <= config.totalOrders(); id++) {
            OrderState state;
            if (!OutcomeDecider.isModelValid(id, config)) {
                state = OrderState.REJECTED;
            } else if (!OutcomeDecider.isPrintSuccessful(id, config)) {
                state = OrderState.PRINT_FAILED;
            } else if (!OutcomeDecider.isQualityApproved(id, config)) {
                state = OrderState.DEFECTIVE;
            } else {
                state = OrderState.APPROVED;
            }
            expected.put(id, state);
        }
        return expected;
    }

    private void checkResult(Map<Integer, OrderState> expected) {
        if (!result.allThreadsTerminated()) {
            fail("allThreadsTerminated=false: quedaron hilos vivos");
        }
        if (result.totalOrders() != config.totalOrders()) {
            fail("totalOrders=" + result.totalOrders() + ", se esperaba " + config.totalOrders());
        }
        if (result.processedOrders() != config.totalOrders()) {
            fail("processedOrders=" + result.processedOrders() + ", se esperaba " + config.totalOrders());
        }

        // inv 2, 3 y 9: cada orden aparece una vez, termina bien y con los contadores justos
        Set<Integer> seen = new HashSet<>();
        Map<OrderState, Integer> counted = new EnumMap<>(OrderState.class);
        for (OrderSnapshot order : result.orders()) {
            if (!seen.add(order.id())) {
                fail("[inv 1] la orden " + order.id() + " aparece dos veces en el resultado");
            }
            counted.merge(order.state(), 1, Integer::sum);
            if (!order.state().isTerminal()) {
                fail("[inv 9] la orden " + order.id() + " termino en estado intermedio " + order.state());
            }
            OrderState wanted = expected.get(order.id());
            if (wanted == null) {
                fail("[inv 2] orden con id fuera de rango: " + order.id());
                continue;
            }
            if (order.state() != wanted) {
                fail("[inv 2] la orden " + order.id() + " termino en " + order.state()
                        + " pero OutcomeDecider dice " + wanted);
            }
            checkCounters(order.id(), order.state(), order.assignmentCount(), order.validationCount(),
                    order.printingCount(), order.qualityControlCount(), "resultado");
            if (order.assignedPrinterId() == null) {
                fail("[inv 5] la orden " + order.id() + " no conserva la impresora historica");
            }
        }
        if (seen.size() != config.totalOrders()) {
            fail("[inv 2] el resultado tiene " + seen.size() + " ordenes distintas, se esperaban "
                    + config.totalOrders());
        }

        // totalsByState tiene que cerrar con lo que dicen las ordenes
        for (OrderState state : OrderState.values()) {
            int reported = result.totalsByState().getOrDefault(state, 0);
            int real = counted.getOrDefault(state, 0);
            if (reported != real) {
                fail("totalsByState[" + state + "]=" + reported + " pero hay " + real + " ordenes en ese estado");
            }
        }

        // inv 7 y 10: ninguna RESERVED al final, y tantas rotas como PRINT_FAILED
        if (result.printers().size() != config.totalPrinters()) {
            fail("hay " + result.printers().size() + " impresoras, se esperaban " + config.totalPrinters());
        }
        Set<String> printerIds = new HashSet<>();
        int outOfService = 0;
        for (PrinterSnapshot printer : result.printers()) {
            if (!printerIds.add(printer.id())) {
                fail("id de impresora repetido: " + printer.id());
            }
            if (printer.state() == PrinterState.RESERVED) {
                fail("[inv 10] la impresora " + printer.id() + " quedo RESERVED");
            }
            if (printer.assignedOrderId() != null) {
                fail("[inv 6] la impresora " + printer.id() + " sigue asignada a la orden "
                        + printer.assignedOrderId());
            }
            if (printer.state() == PrinterState.OUT_OF_SERVICE) {
                outOfService++;
            }
        }
        int printFailed = counted.getOrDefault(OrderState.PRINT_FAILED, 0);
        if (outOfService != printFailed) {
            fail("[inv 7] hay " + outOfService + " impresoras OUT_OF_SERVICE pero " + printFailed
                    + " ordenes PRINT_FAILED");
        }
    }

    // inv 3: segun como termino, cuantas veces tuvo que pasar por cada etapa
    private void checkCounters(int id, OrderState state, int assignment, int validation,
                               int printing, int quality, String source) {
        int expectedPrinting = state == OrderState.REJECTED ? 0 : 1;
        int expectedQuality = (state == OrderState.APPROVED || state == OrderState.DEFECTIVE) ? 1 : 0;
        if (assignment != 1 || validation != 1 || printing != expectedPrinting || quality != expectedQuality) {
            fail("[inv 3] contadores de la orden " + id + " (" + source + ", " + state + "): "
                    + assignment + "/" + validation + "/" + printing + "/" + quality
                    + ", se esperaba 1/1/" + expectedPrinting + "/" + expectedQuality);
        }
    }

    // ---- eventos.csv ----

    // Recorre el log evento por evento. De paso cuenta cuantas veces se asigno cada
    // impresora, para despues compararlo con usageCount (inv 8).
    private Map<String, Integer> checkEvents(Map<Integer, OrderState> expected) throws IOException {
        List<String> lines = Files.readAllLines(file("eventos.csv"), StandardCharsets.UTF_8);
        Map<String, Integer> assignmentsPerPrinter = new HashMap<>();
        if (lines.isEmpty() || !lines.getFirst().equals(EVENT_HEADER)) {
            fail("eventos.csv no tiene la cabecera exacta");
            return assignmentsPerPrinter;
        }

        Map<Integer, OrderState> stateOf = new HashMap<>();   // en que estado esta cada orden
        Map<Integer, String> printerOf = new HashMap<>();     // impresora que uso cada orden
        Map<String, Integer> holderOf = new HashMap<>();      // quien tiene cada impresora ahora
        Set<String> broken = new HashSet<>();                 // las que quedaron fuera de servicio
        long previousSequence = 0;

        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            String where = "eventos.csv linea " + lineNumber + ": ";
            String[] f = line.split(";", -1);
            if (f.length != 9) {
                fail(where + "tiene " + f.length + " campos en vez de 9");
                continue;
            }

            long sequence;
            long elapsed;
            int id;
            try {
                sequence = Long.parseLong(f[0]);
                elapsed = Long.parseLong(f[1]);
                id = Integer.parseInt(f[3]);
            } catch (NumberFormatException exception) {
                fail(where + "numero invalido");
                continue;
            }
            if (sequence != previousSequence + 1) {
                fail(where + "sequence=" + sequence + " despues de " + previousSequence
                        + " (tiene que ser creciente, unica y sin huecos)");
            }
            previousSequence = sequence;
            if (elapsed < 0) {
                fail(where + "elapsedMs negativo");
            }
            if (f[2].isBlank()) {
                fail(where + "thread vacio");
            }
            if (!expected.containsKey(id)) {
                fail(where + "orderId fuera de rango: " + id);
                continue;
            }

            String stage = f[4];
            String event = f[5];
            String printer = f[8];

            if (event.equals("ORDER_CREATED")) {
                if (!stage.equals("INITIALIZATION") || !f[6].isEmpty() || !f[7].equals("CREATED")
                        || !printer.isEmpty()) {
                    fail(where + "ORDER_CREATED mal formado: " + line);
                }
                if (stateOf.containsKey(id)) {
                    fail(where + "[inv 1] la orden " + id + " se crea dos veces");
                }
                stateOf.put(id, OrderState.CREATED);
                continue;
            }
            if (!event.equals("ORDER_STATE_CHANGED")) {
                fail(where + "evento desconocido: " + event);
                continue;
            }

            OrderState from;
            OrderState to;
            try {
                from = OrderState.valueOf(f[6]);
                to = OrderState.valueOf(f[7]);
            } catch (IllegalArgumentException exception) {
                fail(where + "estado desconocido: " + line);
                continue;
            }

            // inv 1: el "from" del evento tiene que ser el estado en que estaba la orden
            OrderState current = stateOf.get(id);
            if (current == null) {
                fail(where + "[inv 1] la orden " + id + " cambia de estado antes de ser creada");
            } else if (current != from) {
                fail(where + "[inv 1] la orden " + id + " pasa de " + from + " a " + to
                        + " pero estaba en " + current);
            }
            String allowedStage = ALLOWED.getOrDefault(from, Map.of()).get(to);
            if (allowedStage == null) {
                fail(where + "[inv 2/3] transicion no permitida " + from + " -> " + to
                        + " (orden " + id + ")");
            } else if (!allowedStage.equals(stage)) {
                fail(where + "la transicion " + from + " -> " + to + " deberia ser de la etapa "
                        + allowedStage + ", no de " + stage);
            }
            stateOf.put(id, to);

            if (printer.isEmpty()) {
                fail(where + "[inv 5] la orden " + id + " pasa a " + to + " sin impresora informada");
                continue;
            }

            if (to == OrderState.WAITING_VALIDATION) {
                // asignacion: la impresora tiene que estar libre y no rota (inv 4 y 7)
                if (broken.contains(printer)) {
                    fail(where + "[inv 7] se reutiliza la impresora " + printer + " que estaba OUT_OF_SERVICE");
                }
                Integer holder = holderOf.get(printer);
                if (holder != null) {
                    fail(where + "[inv 4] la impresora " + printer + " se asigna a la orden " + id
                            + " mientras la tiene la orden " + holder);
                }
                holderOf.put(printer, id);
                printerOf.put(id, printer);
                assignmentsPerPrinter.merge(printer, 1, Integer::sum);
                continue;
            }

            // en el resto de los eventos tiene que aparecer la misma impresora que se le asigno
            String historic = printerOf.get(id);
            if (!printer.equals(historic)) {
                fail(where + "la orden " + id + " informa la impresora " + printer + " pero se le asigno "
                        + historic);
            }

            // inv 5: si estaba esperando validacion o lista para imprimir, la impresora tiene que ser suya
            if ((from == OrderState.WAITING_VALIDATION || from == OrderState.READY_TO_PRINT)
                    && !Integer.valueOf(id).equals(holderOf.get(printer))) {
                fail(where + "[inv 5] la orden " + id + " esta en " + from + " pero la impresora "
                        + printer + " no esta reservada para ella");
            }

            // inv 6: cuando termina (o cuando imprime) la orden suelta la impresora
            if (to == OrderState.REJECTED || to == OrderState.PRINTED || to == OrderState.PRINT_FAILED) {
                if (Integer.valueOf(id).equals(holderOf.get(printer))) {
                    holderOf.remove(printer);
                }
                if (to == OrderState.PRINT_FAILED) {
                    broken.add(printer);
                }
            }
        }

        // inv 9 mirando el log: todas se crearon y todas terminaron
        for (int id = 1; id <= config.totalOrders(); id++) {
            OrderState state = stateOf.get(id);
            if (state == null) {
                fail("[inv 2] eventos.csv no registra la creacion de la orden " + id);
            } else if (!state.isTerminal()) {
                fail("[inv 9] segun eventos.csv la orden " + id + " quedo en " + state);
            } else if (state != expected.get(id)) {
                fail("[inv 2] segun eventos.csv la orden " + id + " termino en " + state
                        + " pero OutcomeDecider dice " + expected.get(id));
            }
        }
        // inv 6 y 10 mirando el log: al final nadie se quedo con una impresora
        if (!holderOf.isEmpty()) {
            fail("[inv 6/10] segun eventos.csv quedaron impresoras retenidas: " + holderOf);
        }
        return assignmentsPerPrinter;
    }

    // inv 8: el usageCount de cada impresora tiene que dar igual a las veces que se asigno en el log
    private void checkPrinterUsage(Map<String, Integer> assignmentsPerPrinter) {
        int totalUses = 0;
        for (PrinterSnapshot printer : result.printers()) {
            totalUses += printer.usageCount();
            int logged = assignmentsPerPrinter.getOrDefault(printer.id(), 0);
            if (printer.usageCount() != logged) {
                fail("[inv 8] la impresora " + printer.id() + " informa usageCount=" + printer.usageCount()
                        + " pero eventos.csv registra " + logged + " asignaciones");
            }
        }
        if (totalUses != config.totalOrders()) {
            fail("[inv 8] la suma de usageCount es " + totalUses + ", se esperaba " + config.totalOrders());
        }
    }

    // ---- elementos.csv y resumen.properties ----

    private void checkOrdersFile() throws IOException {
        List<String> lines = Files.readAllLines(file("elementos.csv"), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(ORDER_HEADER)) {
            fail("elementos.csv no tiene la cabecera exacta");
            return;
        }
        if (lines.size() != config.totalOrders() + 1) {
            fail("elementos.csv tiene " + (lines.size() - 1) + " filas, se esperaban " + config.totalOrders());
        }
        Map<Integer, OrderSnapshot> byId = new HashMap<>();
        for (OrderSnapshot order : result.orders()) {
            byId.put(order.id(), order);
        }
        int previousId = 0;
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String where = "elementos.csv linea " + lineNumber + ": ";
            String[] f = lines.get(lineNumber - 1).split(";", -1);
            if (f.length != 7) {
                fail(where + "tiene " + f.length + " campos en vez de 7");
                continue;
            }
            try {
                int id = Integer.parseInt(f[0]);
                if (id != previousId + 1) {
                    fail(where + "no esta ordenado por orderId (" + id + " despues de " + previousId + ")");
                }
                previousId = id;
                OrderState state = OrderState.valueOf(f[1]);
                checkCounters(id, state, Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                        Integer.parseInt(f[5]), Integer.parseInt(f[6]), "elementos.csv");
                OrderSnapshot snapshot = byId.get(id);
                if (snapshot != null && (snapshot.state() != state
                        || !f[2].equals(String.valueOf(snapshot.assignedPrinterId())))) {
                    fail(where + "no coincide con el SimulationResult de la orden " + id);
                }
            } catch (IllegalArgumentException exception) {
                fail(where + "valor invalido");
            }
        }
    }

    private void checkSummaryFile() throws IOException {
        Properties summary = new Properties();
        try (Reader reader = Files.newBufferedReader(file("resumen.properties"), StandardCharsets.UTF_8)) {
            summary.load(reader);
        }
        Map<String, Integer> totals = Map.of(
                "approvedOrders", result.totalsByState().getOrDefault(OrderState.APPROVED, 0),
                "rejectedOrders", result.totalsByState().getOrDefault(OrderState.REJECTED, 0),
                "printFailedOrders", result.totalsByState().getOrDefault(OrderState.PRINT_FAILED, 0),
                "defectiveOrders", result.totalsByState().getOrDefault(OrderState.DEFECTIVE, 0));
        expectProperty(summary, "totalOrders", String.valueOf(config.totalOrders()));
        expectProperty(summary, "processedOrders", String.valueOf(config.totalOrders()));
        expectProperty(summary, "allThreadsTerminated", "true");
        expectProperty(summary, "remainingIntermediateOrders", "0");
        totals.forEach((key, value) -> expectProperty(summary, key, String.valueOf(value)));
        if (summary.getProperty("durationMillis") == null) {
            fail("resumen.properties no tiene durationMillis");
        }
    }

    private void expectProperty(Properties summary, String key, String value) {
        String actual = summary.getProperty(key);
        if (!value.equals(actual)) {
            fail("resumen.properties: " + key + "=" + actual + ", se esperaba " + value);
        }
    }

    // ---- auxiliares ----

    private Path file(String name) {
        return config.outputDirectory().resolve(name);
    }

    private void fail(String message) {
        if (violations.size() < MAX_VIOLATIONS) {
            violations.add(message);
        } else if (violations.size() == MAX_VIOLATIONS) {
            violations.add("... (se omiten las demas violaciones)");
        }
    }

}

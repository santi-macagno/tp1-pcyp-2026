package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterState;
import ar.edu.unc.fcefyn.pcp.tp1.api.Simulation;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;
import ar.edu.unc.fcefyn.pcp.tp1.solution.log.EventLog;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Printer;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Stage;
import ar.edu.unc.fcefyn.pcp.tp1.solution.output.ResultWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;


public final class ConcurrentSimulation implements Simulation {

    @Override
    public SimulationResult execute(SimulationConfig config) throws InterruptedException {
        long startNanos = System.nanoTime();
        EventLog log = new EventLog(startNanos);

        List<Order> orders = new ArrayList<>(config.totalOrders());
        for (int id = 1; id <= config.totalOrders(); id++) {
            Order order = new Order(id);
            orders.add(order);
            log.created(order);
        }

        
        List<Printer> printers = createPrinters(config);

        for (Order order : orders) {
            processSequentially(order, printers, log, config);
        }

        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        SimulationResult result = buildResult(config, orders, printers, durationMillis);

        try {
            ResultWriter.write(config.outputDirectory(), log.lines(), result);
        } catch (IOException exception) {
            throw new UncheckedIOException("No se pudieron escribir los resultados", exception);
        }
        return result;
    }

    private void processSequentially(Order order, List<Printer> printers, EventLog log,
                                     SimulationConfig config) throws InterruptedException {
        // Etapa 1: asignacion
        Printer printer = findAvailable(printers);
        printer.reserve(order.getId());
        delay(config.assignmentDelayMillis());
        order.assign(printer);
        order.setAssignmentCount();
        log.changed(order, Stage.ASSIGNMENT, OrderState.CREATED, OrderState.WAITING_VALIDATION);

        // Etapa 2: validacion
        delay(config.validationDelayMillis());
        order.setValidationCount();
        if (!OutcomeDecider.isModelValid(order.getId(), config)) {
            log.changed(order, Stage.VALIDATION, OrderState.WAITING_VALIDATION, OrderState.REJECTED);
            printer.release();
            return;
        }
        log.changed(order, Stage.VALIDATION, OrderState.WAITING_VALIDATION, OrderState.READY_TO_PRINT);

        // Etapa 3: impresion
        delay(config.printingDelayMillis());
        order.setPrintingCount();
        if (!OutcomeDecider.isPrintSuccessful(order.getId(), config)) {
            log.changed(order, Stage.PRINTING, OrderState.READY_TO_PRINT, OrderState.PRINT_FAILED);
            printer.disable();
            return;
        }
        log.changed(order, Stage.PRINTING, OrderState.READY_TO_PRINT, OrderState.PRINTED);
        printer.release();

        // Etapa 4: control de calidad
        delay(config.qualityControlDelayMillis());
        order.setQualityControlCount();
        OrderState result = OutcomeDecider.isQualityApproved(order.getId(), config)
                ? OrderState.APPROVED
                : OrderState.DEFECTIVE;
        log.changed(order, Stage.QUALITY_CONTROL, OrderState.PRINTED, result);
    }

    private List<Printer> createPrinters(SimulationConfig config) {
        List<Printer> printers = new ArrayList<>(config.totalPrinters());
        for (int row = 0; row < config.printerRows(); row++) {
            for (int column = 0; column < config.printerColumns(); column++) {
                printers.add(new Printer(row, column));
            }
        }
        return printers;
    }

    private Printer findAvailable(List<Printer> printers) {
        for (Printer printer : printers) {
            if (printer.getState() == PrinterState.AVAILABLE) {
                return printer;
            }
        }
        throw new IllegalStateException("No quedan impresoras disponibles");
    }

    private void delay(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private SimulationResult buildResult(SimulationConfig config, List<Order> orders,
                                         List<Printer> printers, long durationMillis) {
        List<OrderSnapshot> orderSnapshots = new ArrayList<>(orders.size());
        Map<OrderState, Integer> totals = new EnumMap<>(OrderState.class);
        for (OrderState state : OrderState.values()) {
            totals.put(state, 0);
        }
        int processed = 0;
        for (Order order : orders) {
            OrderSnapshot snapshot = order.toSnapshot();
            orderSnapshots.add(snapshot);
            totals.merge(snapshot.state(), 1, Integer::sum);
            if (snapshot.state().isTerminal()) {
                processed++;
            }
        }

        List<PrinterSnapshot> printerSnapshots = new ArrayList<>(printers.size());
        for (Printer printer : printers) {
            printerSnapshots.add(printer.toSnapshot());
        }

        // Version secuencial: no se crean hilos, asi que no puede quedar ninguno vivo.
        boolean allThreadsTerminated = true;

        return new SimulationResult(config.totalOrders(), processed, totals,
                orderSnapshots, printerSnapshots, durationMillis, allThreadsTerminated);
    }
}

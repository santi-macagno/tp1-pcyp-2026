package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.Simulation;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;
import ar.edu.unc.fcefyn.pcp.tp1.solution.log.EventLog;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.OrderQueue;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.PrinterFarm;
import ar.edu.unc.fcefyn.pcp.tp1.solution.output.ResultWriter;
import ar.edu.unc.fcefyn.pcp.tp1.solution.workers.AssignmentWorker;
import ar.edu.unc.fcefyn.pcp.tp1.solution.workers.PrintingWorker;
import ar.edu.unc.fcefyn.pcp.tp1.solution.workers.QualityControlWorker;
import ar.edu.unc.fcefyn.pcp.tp1.solution.workers.ValidationWorker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;


public final class ConcurrentSimulation implements Simulation {

    @Override
    public SimulationResult execute(SimulationConfig config) throws InterruptedException {
        long startNanos = System.nanoTime();
        EventLog log = new EventLog(startNanos);
        PrinterFarm farm = new PrinterFarm(config);
        OrderQueue assignmentQueue = new OrderQueue();
        OrderQueue validationQueue = new OrderQueue();
        OrderQueue printingQueue = new OrderQueue();
        OrderQueue qualityQueue = new OrderQueue();

        List<Order> orders = new ArrayList<>(config.totalOrders());
        for (int id = 1; id <= config.totalOrders(); id++) {
            Order order = new Order(id);
            orders.add(order);
            log.created(order);
            assignmentQueue.put(order);
        }
        assignmentQueue.close();

        List<Thread> assignment = startThreads("assignment", config.assignmentThreads(),
                () -> new AssignmentWorker(assignmentQueue, validationQueue, farm, log, config));
        List<Thread> validation = startThreads("validation", config.validationThreads(),
                () -> new ValidationWorker(validationQueue, printingQueue, farm, log, config));
        List<Thread> printing = startThreads("printing", config.printingThreads(),
                () -> new PrintingWorker(printingQueue, qualityQueue, farm, log, config));
        List<Thread> quality = startThreads("quality-control", config.qualityControlThreads(),
                () -> new QualityControlWorker(qualityQueue, log, config));

        joinAll(assignment);
        validationQueue.close();
        joinAll(validation);
        printingQueue.close();
        joinAll(printing);
        qualityQueue.close();
        joinAll(quality);

        List<Thread> all = new ArrayList<>();
        all.addAll(assignment);
        all.addAll(validation);
        all.addAll(printing);
        all.addAll(quality);
        boolean allThreadsTerminated = true;
        for (Thread thread : all) {
            if (thread.isAlive()) {
                allThreadsTerminated = false;
            }
        }

        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        SimulationResult result = buildResult(config, orders, farm.snapshots(),
                durationMillis, allThreadsTerminated);

        try {
            ResultWriter.write(config.outputDirectory(), log.lines(), result);
        } catch (IOException exception) {
            throw new UncheckedIOException("No se pudieron escribir los resultados", exception);
        }
        return result;
    }

    private List<Thread> startThreads(String prefix, int count, Supplier<Runnable> workerFactory) {
        List<Thread> threads = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            Thread thread = new Thread(workerFactory.get(), prefix + "-" + i);
            threads.add(thread);
            thread.start();
        }
        return threads;
    }

    private void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private SimulationResult buildResult(SimulationConfig config, List<Order> orders,
                                         List<PrinterSnapshot> printerSnapshots,
                                         long durationMillis, boolean allThreadsTerminated) {
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

        return new SimulationResult(config.totalOrders(), processed, totals,
                orderSnapshots, printerSnapshots, durationMillis, allThreadsTerminated);
    }
}
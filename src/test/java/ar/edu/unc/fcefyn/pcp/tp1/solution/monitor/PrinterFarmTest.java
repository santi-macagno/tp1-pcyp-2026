package ar.edu.unc.fcefyn.pcp.tp1.solution.monitor;

import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterState;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Printer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrinterFarmTest {

    @TempDir
    Path outputDirectory;

    // El constructor crea filas x columnas impresoras P-fila-columna, todas disponibles.
    @Test
    void createsAllPrintersAvailable() {
        PrinterFarm farm = new PrinterFarm(config(2, 3));

        List<PrinterSnapshot> snapshots = farm.snapshots();
        assertEquals(6, snapshots.size());
        assertEquals("P-0-0", snapshots.get(0).id());
        assertEquals("P-1-2", snapshots.get(5).id());
        for (PrinterSnapshot printer : snapshots) {
            assertEquals(PrinterState.AVAILABLE, printer.state());
            assertEquals(0, printer.usageCount());
            assertNull(printer.assignedOrderId());
        }
    }

    // Muchos hilos reservando a la vez: nunca dos ordenes con la misma impresora,
    // y el total de usos coincide con la cantidad de reservas (invariantes 4 y 8).
    @RepeatedTest(10)
    void neverAssignsTheSamePrinterTwice() {
        int threads = 8;
        int reservationsPerThread = 200;
        PrinterFarm farm = new PrinterFarm(config(1, 3));
        Set<String> inUse = new HashSet<>();
        List<String> errors = new ArrayList<>();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int firstId = t * reservationsPerThread + 1;
                workers.add(new Thread(() -> {
                    try {
                        for (int id = firstId; id < firstId + reservationsPerThread; id++) {
                            Printer printer = farm.reserve(new Order(id));
                            synchronized (inUse) {
                                if (!inUse.add(printer.getId())) {
                                    errors.add(printer.getId() + " asignada dos veces");
                                }
                            }
                            Thread.yield();
                            synchronized (inUse) {
                                inUse.remove(printer.getId());
                            }
                            farm.release(printer);
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            workers.forEach(Thread::start);
            for (Thread worker : workers) {
                worker.join();
            }
        });

        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
        int totalUses = farm.snapshots().stream().mapToInt(PrinterSnapshot::usageCount).sum();
        assertEquals(threads * reservationsPerThread, totalUses);
        for (PrinterSnapshot printer : farm.snapshots()) {
            assertEquals(PrinterState.AVAILABLE, printer.state());
            assertNull(printer.assignedOrderId());
        }
    }

    // 1 impresora y 8 ordenes: todas la reutilizan. El segundo hilo espera hasta la liberacion.
    @Test
    void onePrinterIsReusedByEightOrders() {
        PrinterFarm farm = new PrinterFarm(config(1, 1));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Printer first = farm.reserve(new Order(1));

            Printer[] obtained = new Printer[1];
            Thread waiting = new Thread(() -> {
                try {
                    obtained[0] = farm.reserve(new Order(2));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            waiting.start();
            while (waiting.getState() != Thread.State.WAITING) {
                Thread.sleep(5);
            }

            farm.release(first);
            waiting.join();
            assertSame(first, obtained[0]);
            farm.release(obtained[0]);

            for (int id = 3; id <= 8; id++) {
                farm.release(farm.reserve(new Order(id)));
            }
        });

        PrinterSnapshot printer = farm.snapshots().getFirst();
        assertEquals(8, printer.usageCount());
        assertEquals(PrinterState.AVAILABLE, printer.state());
        assertNull(printer.assignedOrderId());
    }

    // Una impresora OUT_OF_SERVICE no vuelve a asignarse (invariante 7).
    @Test
    void disabledPrinterIsNeverReassigned() throws InterruptedException {
        PrinterFarm farm = new PrinterFarm(config(1, 2));

        Printer broken = farm.reserve(new Order(1));
        farm.disable(broken);

        for (int id = 2; id <= 20; id++) {
            Printer printer = farm.reserve(new Order(id));
            assertNotEquals(broken.getId(), printer.getId());
            farm.release(printer);
        }

        PrinterSnapshot snapshot = farm.snapshots().getFirst();
        assertEquals(broken.getId(), snapshot.id());
        assertEquals(PrinterState.OUT_OF_SERVICE, snapshot.state());
        assertEquals(1, snapshot.usageCount());
        assertNull(snapshot.assignedOrderId());
    }

    private SimulationConfig config(int rows, int columns) {
        return new SimulationConfig(
                10, rows, columns,
                1, 1, 1, 1,
                100, 100, 100,
                0, 0, 0, 0,
                1L,
                outputDirectory
        );
    }
}

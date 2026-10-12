package ar.edu.unc.fcefyn.pcp.tp1.solution.monitor;

import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterState;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Printer;

import java.util.ArrayList;
import java.util.List;
// Pool de impresoras (monitor propio). Es el unico lugar donde se llaman los metodos
// de Printer, siempre con el lock de esta clase tomado.
public class PrinterFarm {

    private final List<Printer> printers = new ArrayList<>();

    public PrinterFarm(SimulationConfig config) {
        for (int row = 0; row < config.printerRows(); row++) {
            for (int column = 0; column < config.printerColumns(); column++) {
                printers.add(new Printer(row, column));
            }
        }
    }

    // Buscar y reservar en la misma seccion critica: dos hilos nunca obtienen la misma impresora.
    public synchronized Printer reserve(Order order) throws InterruptedException {
        Printer printer = findAvailable();
        while (printer == null) {
            wait();
            printer = findAvailable();
        }
        printer.reserve(order.getId());
        return printer;
    }

    public synchronized void release(Printer printer) {
        printer.release();
        notifyAll();
    }

    // No despierta a nadie: una impresora fuera de servicio no se vuelve a usar.
    public synchronized void disable(Printer printer) {
        printer.disable();
    }

    public synchronized List<PrinterSnapshot> snapshots() {
        List<PrinterSnapshot> snapshots = new ArrayList<>(printers.size());
        for (Printer printer : printers) {
            snapshots.add(printer.toSnapshot());
        }
        return snapshots;
    }

    private Printer findAvailable() {
        for (Printer printer : printers) {
            if (printer.getState() == PrinterState.AVAILABLE) {
                return printer;
            }
        }
        return null;
    }
}

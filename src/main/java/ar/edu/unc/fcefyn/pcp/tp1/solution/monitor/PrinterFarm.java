package ar.edu.unc.fcefyn.pcp.tp1.solution.monitor;

import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Printer;

import java.util.List;
// Pool de impresoras (monitor propio). Es el unico lugar donde se llaman los metodos
// de Printer, siempre con el lock de esta clase tomado.
public class PrinterFarm {
    public PrinterFarm(SimulationConfig config) {
        throw new UnsupportedOperationException();
    }

    public Printer reserve(Order order) throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    public void release(Printer printer) {
        throw new UnsupportedOperationException();
    }

    public void disable(Printer printer) {
        throw new UnsupportedOperationException();
    }

    public List<PrinterSnapshot> snapshots() {
        throw new UnsupportedOperationException();
    }
}

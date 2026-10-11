package ar.edu.unc.fcefyn.pcp.tp1.solution.log;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Printer;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Stage;

import java.util.ArrayList;
import java.util.List;

// Registro de eventos para eventos.csv. Recurso compartido por todos los hilos:
// sequence y lines se acceden siempre con el lock de esta instancia tomado.
//
// recordTransition() cambia el estado de la orden y escribe su fila dentro del mismo
// lock, asi el orden de las filas coincide con el orden en que se confirman las transiciones.
//
// Orden de locks: siempre log -> orden. Ningun metodo synchronized de Order puede
// llamar al log, porque se podria formar un deadlock con un hilo que tome los locks al reves.
public class EventLog {

    private final long startNanos;
    private final List<String> lines = new ArrayList<>();
    private long sequence;

    public EventLog(long startNanos) {
        this.startNanos = startNanos;
    }

    public synchronized void created(Order order) {
        append(order, Stage.INITIALIZATION, "ORDER_CREATED", null, OrderState.CREATED, null);
    }

    // Hace la transicion de la orden y la registra de forma atomica.
    // Los workers NO deben llamar a order.transition() por su cuenta.
    public synchronized void recordTransition(Order order, Stage stage, OrderState from, OrderState to) {
        order.transition(from, to);
        Printer printer = order.getPrinter();
        append(order, stage, "ORDER_STATE_CHANGED", from, to, printer == null ? null : printer.getId());
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    // Solo se llama desde metodos synchronized: requiere el lock de this tomado.
    private void append(Order order, Stage stage, String event,
                        OrderState from, OrderState to, String printerId) {
        sequence++;
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        lines.add(sequence + ";" + elapsedMs + ";" + Thread.currentThread().getName() + ";"
                + order.getId() + ";" + stage + ";" + event + ";"
                + (from == null ? "" : from) + ";" + to + ";"
                + (printerId == null ? "" : printerId));
    }
}

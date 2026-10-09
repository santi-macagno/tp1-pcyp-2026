package ar.edu.unc.fcefyn.pcp.tp1.solution.log;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Printer;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Stage;

import java.util.ArrayList;
import java.util.List;

public class EventLog {

    private static final String ORDER_CREATED = "ORDER_CREATED";
    private static final String ORDER_STATE_CHANGED = "ORDER_STATE_CHANGED";

    private final long startNanos;
    private long sequence = 0;
    private final List<String> lines = new ArrayList<>();

    public EventLog(long startNanos) {
        this.startNanos = startNanos;
    }

    public void created(Order order) {
        append(order.getId(), Stage.INITIALIZATION, ORDER_CREATED,
                "", OrderState.CREATED.name(), "");
    }

    public void changed(Order order, Stage stage, OrderState from, OrderState to) {

        Printer printer = order.getPrinter();
        String printerId = printer == null ? "" : printer.getId();

        append(order.getId(), stage, ORDER_STATE_CHANGED, from.name(), to.name(), printerId);
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    private synchronized void append(int orderId, Stage stage, String event,
                                     String from, String to, String printerId) {
        sequence++;
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        String thread = Thread.currentThread().getName();

        lines.add(String.join(";",
                Long.toString(sequence),
                Long.toString(elapsedMs),
                thread,
                Integer.toString(orderId),
                stage.name(),
                event,
                from,
                to,
                printerId));
    }
}

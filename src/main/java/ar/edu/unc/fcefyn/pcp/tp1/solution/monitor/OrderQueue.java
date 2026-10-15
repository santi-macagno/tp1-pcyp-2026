package ar.edu.unc.fcefyn.pcp.tp1.solution.monitor;

import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;

import java.util.ArrayList;
import java.util.List;

// Cola entre etapas (monitor propio). Todos los accesos a "orders" y "closed"
// se hacen con el lock de esta instancia tomado.
public class OrderQueue {

    private final List<Order> orders = new ArrayList<>();
    private boolean closed;

    public synchronized void put(Order order) {
        if (closed) {
            throw new IllegalStateException("No se puede encolar en una cola cerrada");
        }
        orders.addLast(order);
        notifyAll();
    }

    // Bloquea mientras la cola este vacia y abierta. Devuelve null si esta cerrada y vacia:
    // es la señal para que el worker termine.
    public synchronized Order take() throws InterruptedException {
        while (orders.isEmpty() && !closed) {
            wait();
        }
        if(orders.isEmpty()) {
            return null; // Si esta cerrada y vacía
        }
        return orders.removeFirst();
    }

    // Se llama cuando ya terminaron todos los productores de esta cola.
    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}

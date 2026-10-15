package ar.edu.unc.fcefyn.pcp.tp1.solution.workers;

import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.OrderQueue;

public abstract class StageWorker implements Runnable {
    private final OrderQueue input;
    private final long delayMillis;

    protected StageWorker(OrderQueue input, long delayMillis) {
        this.input = input;
        this.delayMillis = delayMillis;
    }

    @Override
    public final void run() {
        try {
            Order order;
            while ((order = input.take()) != null) {
                process(order);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    protected abstract void process(Order order) throws InterruptedException;

    protected void delay() throws InterruptedException {
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
    }
}

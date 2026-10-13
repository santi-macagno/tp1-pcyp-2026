package ar.edu.unc.fcefyn.pcp.tp1.solution.workers;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.solution.log.EventLog;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Printer;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Stage;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.OrderQueue;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.PrinterFarm;

public class assignmentWorker extends stageWorkers{

    private final OrderQueue output;
    private final PrinterFarm farm;
    private final EventLog log;

    public assignmentWorker(OrderQueue input, OrderQueue output, PrinterFarm farm,
                            EventLog log, SimulationConfig config) {
        super(input, config.assignmentDelayMillis());
        this.output = output;
        this.farm = farm;
        this.log = log;
    }

    @Override
    protected void process(Order order) throws InterruptedException {
        Printer printer = farm.reserve(order);
        order.assign(printer);
        delay();
        order.setAssignmentCount();
        log.changed(order, Stage.ASSIGNMENT, OrderState.CREATED, OrderState.WAITING_VALIDATION);
        output.put(order);
    }
}

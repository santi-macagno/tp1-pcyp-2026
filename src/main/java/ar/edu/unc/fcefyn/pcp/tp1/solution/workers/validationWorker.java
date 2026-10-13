package ar.edu.unc.fcefyn.pcp.tp1.solution.workers;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.solution.log.EventLog;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Stage;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.OrderQueue;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.PrinterFarm;

public class validationWorker extends stageWorkers{
    private final OrderQueue output;
    private final PrinterFarm farm;
    private final EventLog log;
    private final SimulationConfig config;

    public validationWorker(OrderQueue input, OrderQueue output, PrinterFarm farm,
                            EventLog log, SimulationConfig config) {
        super(input, config.validationDelayMillis());
        this.output = output;
        this.farm = farm;
        this.log = log;
        this.config = config;
    }

    @Override
    protected void process(Order order) throws InterruptedException {
        delay();
        order.setValidationCount();

        if (OutcomeDecider.isModelValid(order.getId(), config)) {
            log.changed(order, Stage.VALIDATION, OrderState.WAITING_VALIDATION, OrderState.READY_TO_PRINT);
            output.put(order);
        } else {
            log.changed(order, Stage.VALIDATION, OrderState.WAITING_VALIDATION, OrderState.REJECTED);
            farm.release(order.getPrinter());
        }
    }
}

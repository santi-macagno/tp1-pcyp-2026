package ar.edu.unc.fcefyn.pcp.tp1.solution.workers;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.solution.log.EventLog;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Stage;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.OrderQueue;


public class qualityControlWorker extends stageWorkers {

    private final EventLog log;
    private final SimulationConfig config;

    public qualityControlWorker(OrderQueue input, EventLog log, SimulationConfig config) {
        super(input, config.qualityControlDelayMillis());
        this.log = log;
        this.config = config;
    }

    @Override
    protected void process(Order order) throws InterruptedException {
        delay();
        order.setQualityControlCount();
        OrderState result = OutcomeDecider.isQualityApproved(order.getId(), config)
                ? OrderState.APPROVED
                : OrderState.DEFECTIVE;
        log.changed(order, Stage.QUALITY_CONTROL, OrderState.PRINTED, result);
    }
}

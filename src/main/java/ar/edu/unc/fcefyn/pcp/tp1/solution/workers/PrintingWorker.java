package ar.edu.unc.fcefyn.pcp.tp1.solution.workers;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.solution.log.EventLog;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Stage;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.OrderQueue;
import ar.edu.unc.fcefyn.pcp.tp1.solution.monitor.PrinterFarm;

public class PrintingWorker extends StageWorker {

    private final OrderQueue output;
    private final PrinterFarm farm;
    private final EventLog log;
    private final SimulationConfig config;

    public PrintingWorker(OrderQueue input, OrderQueue output, PrinterFarm farm,
                          EventLog log, SimulationConfig config) {
        super(input, config.printingDelayMillis());
        this.output = output;
        this.farm = farm;
        this.log = log;
        this.config = config;
    }

    @Override
    protected void process(Order order) throws InterruptedException {
        delay();
        order.setPrintingCount();

        if (OutcomeDecider.isPrintSuccessful(order.getId(), config)) {
            log.changed(order, Stage.PRINTING, OrderState.READY_TO_PRINT, OrderState.PRINTED);
            farm.release(order.getPrinter());
            output.put(order);
        } else {
            log.changed(order, Stage.PRINTING, OrderState.READY_TO_PRINT, OrderState.PRINT_FAILED);
            farm.disable(order.getPrinter());
        }
    }
}

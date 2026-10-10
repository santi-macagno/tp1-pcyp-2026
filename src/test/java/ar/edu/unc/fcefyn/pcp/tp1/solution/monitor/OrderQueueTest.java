package ar.edu.unc.fcefyn.pcp.tp1.solution.monitor;

import ar.edu.unc.fcefyn.pcp.tp1.solution.model.Order;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class OrderQueueTest {

    private static final int PRODUCERS = 3;
    private static final int CONSUMERS = 4;
    private static final int ORDERS_PER_PRODUCER = 1000;

    // N productores y M consumidores: cada orden sale exactamente una vez.
    @RepeatedTest(10)
    void noOrderIsLostOrDuplicated() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            OrderQueue queue = new OrderQueue();

            List<Thread> producers = new ArrayList<>();
            for (int p = 0; p < PRODUCERS; p++) {
                int firstId = p * ORDERS_PER_PRODUCER + 1;
                producers.add(new Thread(() -> {
                    for (int id = firstId; id < firstId + ORDERS_PER_PRODUCER; id++) {
                        queue.put(new Order(id));
                    }
                }));
            }

            List<List<Integer>> taken = new ArrayList<>();
            List<Thread> consumers = new ArrayList<>();
            for (int c = 0; c < CONSUMERS; c++) {
                List<Integer> mine = new ArrayList<>();
                taken.add(mine);
                consumers.add(new Thread(() -> {
                    try {
                        Order order;
                        while ((order = queue.take()) != null) {
                            mine.add(order.getId());
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            consumers.forEach(Thread::start);
            producers.forEach(Thread::start);
            for (Thread producer : producers) {
                producer.join();
            }
            queue.close();
            for (Thread consumer : consumers) {
                consumer.join();
            }

            Set<Integer> ids = new HashSet<>();
            int total = 0;
            for (List<Integer> mine : taken) {
                total += mine.size();
                ids.addAll(mine);
            }
            int expected = PRODUCERS * ORDERS_PER_PRODUCER;
            assertEquals(expected, total, "Se perdieron o duplicaron ordenes");
            assertEquals(expected, ids.size(), "Hay ordenes repetidas");
        });
    }

    // close() despierta a los consumidores bloqueados y take() les devuelve null.
    @Test
    void closeWakesUpBlockedConsumers() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            OrderQueue queue = new OrderQueue();
            List<Order> results = new ArrayList<>();
            List<Thread> consumers = new ArrayList<>();
            for (int c = 0; c < 3; c++) {
                consumers.add(new Thread(() -> {
                    try {
                        Order order = queue.take();
                        synchronized (results) {
                            results.add(order);
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            consumers.forEach(Thread::start);

            // Esperar a que los tres esten dormidos dentro de take().
            for (Thread consumer : consumers) {
                while (consumer.getState() != Thread.State.WAITING) {
                    Thread.sleep(5);
                }
            }

            queue.close();
            for (Thread consumer : consumers) {
                consumer.join();
                assertFalse(consumer.isAlive());
            }
            assertEquals(3, results.size());
            results.forEach(order -> assertNull(order));
        });
    }

    // Lo que quedaba en la cola se entrega aunque ya este cerrada; despues, null.
    @Test
    void closedQueueStillDeliversRemainingOrders() throws InterruptedException {
        OrderQueue queue = new OrderQueue();
        queue.put(new Order(1));
        queue.put(new Order(2));
        queue.close();

        assertEquals(1, queue.take().getId());
        assertEquals(2, queue.take().getId());
        assertNull(queue.take());
        assertThrows(IllegalStateException.class, () -> queue.put(new Order(3)));
    }
}

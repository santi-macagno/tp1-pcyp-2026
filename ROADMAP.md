# TP1 — Roadmap de trabajo

Orden de trabajo para la granja de impresión 3D. Cada fase termina con algo que **compila y se prueba**.
Marquen las casillas a medida que avanzan.

## Reglas de trabajo

- Nadie pushea a `main` ni a `develop`: se trabaja en una rama propia, se abre un PR contra `develop` y el
  merge lo hace el dueño del repo.
- Al abrir el PR, verificar que el *base repository* sea **nuestro repo y no el de la cátedra** (el repo es un fork).
- Antes de empezar una tarea, avisar al grupo que la tomás, por ejemplo abriendo un PR en borrador (*draft*).
  Así nadie hace lo mismo dos veces.
- Cada PR lo revisa alguien que no lo escribió.
- `mvn clean test` tiene que estar en verde antes de mergear. Maven se corre **siempre desde la raíz del repo**,
  donde está `pom.xml`.
- Quien cierra una fase escribe la sección del informe que le corresponde.
- Prohibido en `src/main/java`: `BlockingQueue`, colecciones concurrentes, `Condition`, latches y barreras, clases
  atómicas, `Collections.synchronized*` y espera activa. Permitido: `Thread`, `synchronized`, `wait`/`notifyAll`,
  `Lock`/`ReentrantLock`, `Semaphore` y colecciones comunes.

## Estructura del código

```
solution/
├── ConcurrentSimulation.java   orquesta: crea todo, arranca hilos, join, arma el resultado
├── model/                      datos del dominio
│   ├── Order.java              estado, impresora histórica, contadores por etapa (métodos synchronized)
│   ├── Printer.java            NO thread-safe a propósito: solo se usa desde PrinterFarm
│   └── Stage.java              enum con las etapas, igual que la columna "stage" del CSV
├── monitor/                    monitores: objetos compartidos entre hilos
│   ├── OrderQueue.java         cola entre etapas (wait/notifyAll)
│   └── PrinterFarm.java        pool de impresoras (wait/notifyAll)
├── log/EventLog.java           transiciones de órdenes + filas de eventos.csv
├── output/ResultWriter.java    escribe eventos.csv, elementos.csv y resumen.properties
└── workers/                    los Runnable de cada etapa
```

**Convenciones:**
- Getters con el estilo `getX()`.
- Contadores de etapa con el estilo `countX()`.
- Hilos con nombres `assignment-1`, `validation-1`, `printing-1`, `quality-control-1`, etc.
- Impresoras con ids `P-fila-columna`, empezando en `P-0-0`.

## Contrato entre clases

Son las firmas que usan los demás. No se cambian sin avisar al grupo.

```java
// monitor/OrderQueue
public void put(Order order);                     // excepción si la cola ya está cerrada
public Order take() throws InterruptedException;  // bloquea; devuelve null si está cerrada Y vacía
public void close();                              // se llama cuando terminaron los productores; despierta a todos

// monitor/PrinterFarm
public PrinterFarm(SimulationConfig config);      // rows x columns impresoras, todas AVAILABLE
public Printer reserve(Order order) throws InterruptedException; // bloquea hasta que haya una AVAILABLE
public void release(Printer printer);             // RESERVED -> AVAILABLE, despierta a quienes esperan
public void disable(Printer printer);             // RESERVED -> OUT_OF_SERVICE
public List<PrinterSnapshot> snapshots();

// model/Order
public void assign(Printer printer);              // una sola vez por orden
public Printer getPrinter();                      // impresora histórica (no se borra al liberarla)
public void countAssignment(); countValidation(); countPrinting(); countQualityControl();
public OrderSnapshot toSnapshot();

// log/EventLog
public EventLog(long startNanos);
public void created(Order order);
public void recordTransition(Order order, Stage stage, OrderState from, OrderState to);
public List<String> lines();

// output/ResultWriter
public static void write(Path outputDirectory, List<String> events, SimulationResult result) throws IOException;
```

**Importante:** los workers **no llaman a `order.transition()`**. La transición la hace `log.recordTransition()`,
que cambia el estado y escribe la fila dentro del mismo lock. Si un worker también la llamara, la orden
transicionaría dos veces y la segunda lanzaría `IllegalStateException`.

## Decisiones de diseño acordadas

1. **Las colas entre etapas son un monitor propio (`OrderQueue`).** `take()` usa `while (vacía && !cerrada) wait()`.
   Siempre es `while`, nunca `if`, y siempre `notifyAll()`.
2. **`PrinterFarm` es un monitor:** `reserve()` espera mientras no haya impresoras disponibles. Todos los cambios a
   una `Printer` pasan por la farm, con su lock tomado.
3. **Terminación:** `main` arranca todos los hilos juntos. Después hace `join` de la etapa 1, cierra la cola de
   validación, hace `join` de la etapa 2, cierra la cola de impresión, y así hasta el final. Un worker termina
   cuando `take()` devuelve `null`.
4. **Orden dentro de cada etapa:** primero `log.recordTransition(...)` (transición y fila juntas), después liberar
   la impresora, después encolar a la etapa siguiente. Si se cambia ese orden, `eventos.csv` puede quedar desordenado.
5. **Orden de locks: siempre log → orden.** `EventLog` toma el lock de la orden mientras tiene el suyo. Ningún método
   `synchronized` de `Order` puede llamar al log.
6. **Las demoras (`Thread.sleep`) van una vez por orden y siempre fuera de cualquier lock.**
7. **Los eventos se guardan en memoria** y los 3 archivos se escriben al final, antes de retornar de `execute`.

## Fases

### Fase 0 — Entorno y repo · *depende de: nada*
- [x] JDK 21 y Maven instalados; `mvn clean test` corre
- [x] Repo con `main` y `develop` protegidos
- [ ] `integrantes.json` completo (faltan usuarios de GitHub)
- [ ] Todos con acceso al repo, también la cátedra

### Fase 1 — Dominio sin hilos · *depende de: 0*
Que el modelo y las salidas funcionen **sin hilos**, para separar los bugs de dominio de los de concurrencia.
- [x] `Printer`
- [x] `Order`
- [x] `Stage`
- [x] Esqueletos de `OrderQueue` y `PrinterFarm`
- [x] `EventLog`
- [x] `ResultWriter`
- [x] Versión secuencial en `ConcurrentSimulation` (un solo hilo recorre las 4 etapas)
- **Listo:** pasan los 5 tests públicos. Ojo: pasan sin concurrencia, así que la suite pública no alcanza.

### Fase 2 — Monitor `OrderQueue` · *depende de: 1*
- [x] `put` / `take` / `close` con `synchronized` + `wait`/`notifyAll`
- [x] Test propio: N productores y M consumidores, sin perder ni duplicar órdenes
- [x] Test propio: `close()` despierta a los consumidores bloqueados y `take()` devuelve `null`
- [ ] Informe: condiciones de espera y despertar

### Fase 3 — Monitor `PrinterFarm` · *depende de: 1. En paralelo con 6*
- [ ] `reserve` / `release` / `disable` / `snapshots`
- [ ] Test propio: muchos hilos reservando, nunca dos órdenes con la misma impresora
- [ ] Test propio: 1 impresora y 8 órdenes (todas la reutilizan)
- [ ] Test propio: una impresora `OUT_OF_SERVICE` no vuelve a asignarse
- [ ] Informe: recurso impresoras, invariantes 4, 7, 8 y 10

### Fase 4 — Workers y orquestación · *depende de: 1, 2 y 3*
- [ ] `AssignmentWorker`, `ValidationWorker`, `PrintingWorker` y `QualityControlWorker`
- [ ] `ConcurrentSimulation`: arranque de todos los hilos, `join` por etapa, cierre de colas y `allThreadsTerminated`
- [ ] Se reemplaza la versión secuencial
- [ ] Informe: arquitectura y mecanismo de terminación

### Fase 5 — Orden del log y salidas finales · *depende de: 4*
- [ ] Verificar el orden *registrar → liberar → encolar* (decisión 4)
- [ ] `mvn clean test` en verde varias veces seguidas
- [ ] Corrida oficial con `java -jar target/tp1.jar`: entre 30 y 45 s
- [ ] Guardar los resultados oficiales en `resultados/`

### Fase 6 — Tests propios · *el verificador se puede empezar ya; correrlo sobre la salida real requiere 5*
- [ ] Verificador de los 10 invariantes sobre `eventos.csv` y `elementos.csv`
- [ ] `@RepeatedTest` para detectar fallas intermitentes
- [ ] Configs extremas: 1 impresora, muchos hilos, demoras en 0, probabilidades en 0 y en 100

### Fase 7 — Experimentos, informe y entrega · *diagramas desde 4; experimentos desde 5*
- [ ] Varias corridas: tiempos teóricos vs observados
- [ ] Variar la cantidad de hilos y las demoras (por ejemplo, 3 hilos de validación)
- [ ] `docs/diagrama-clases.png` y `docs/diagrama-secuencia.png`
- [ ] `docs/informe.pdf` con todas las secciones del enunciado
- [ ] `README.md` con instrucciones de compilación y ejecución
- [ ] Sesión de preguntas tipo defensa con todo el grupo
- [ ] Presentación de 3 minutos
- [ ] Tag `thread-ators-entrega-tp1` y ZIP con `git archive`

## Referencia para verificar

Con la config oficial (`config/tp1.properties`), los estados finales tienen que dar siempre:

| Estado | Cantidad |
|---|---|
| APPROVED | 359 |
| REJECTED | 71 |
| PRINT_FAILED | 45 |
| DEFECTIVE | 25 |

Tiempo teórico por etapa (órdenes × demora / hilos):

| Etapa | Tiempo |
|---|---|
| Asignación | 8,3 s |
| **Validación** | **30,0 s (cuello de botella)** |
| Impresión | 25,7 s |
| Control de calidad | 15,4 s |

El total esperado ronda los 30 s.

# TP1 — Granja concurrente de impresión 3D

Trabajo Práctico 1 de **Programación Concurrente y Paralela 2026** (FCEFyN, UNC). Grupo **thread-ators**.

Simulación concurrente en Java 21 de una granja industrial de impresión 3D. El sistema recibe órdenes de fabricación, les asigna una impresora, valida los modelos, imprime las piezas y controla su calidad. Las cuatro etapas corren al mismo tiempo, cada una con varios hilos, y comparten colas de órdenes, un pool de impresoras y un registro de eventos.

La sincronización se implementa solo con `synchronized`, `wait()` y `notifyAll()`, y los hilos se crean con `Thread`. No se usan otras clases de `java.util.concurrent`, colecciones concurrentes ni clases atómicas.

## Integrantes

Ver [`integrantes.json`](integrantes.json).

## Requisitos

- Java 21
- Maven 3.9 o compatible

El proyecto no tiene dependencias de producción. JUnit 5 se usa solo en los tests.

## Compilación y ejecución

Todos los comandos se ejecutan desde la raíz del repositorio, donde está `pom.xml`.

Ejecutar los tests (la suite pública y los tests del grupo):

```bash
mvn clean test
```

Compilar y generar el JAR:

```bash
mvn clean package
```

Ejecutar la simulación con la configuración oficial:

```bash
java -jar target/tp1.jar
```

El programa lee siempre `config/tp1.properties` y no recibe argumentos. Con la configuración oficial tarda unos 30 segundos y termina imprimiendo:

```
Procesadas 500 de 500 ordenes en 30xxx ms
```

## Configuración

`config/tp1.properties` define la cantidad de órdenes, el tamaño de la matriz de impresoras, la cantidad de hilos de cada etapa, las probabilidades de éxito, las demoras y el directorio de salida. Ninguno de esos valores está escrito en el código: para probar otra configuración, alcanza con editar ese archivo.

| Parámetro | Valor oficial |
|---|---|
| Órdenes | 500 |
| Impresoras | 10 × 20 = 200 |
| Hilos (asignación / validación / impresión / calidad) | 3 / 2 / 3 / 2 |
| Modelos válidos / impresiones exitosas / piezas aprobadas | 85 % / 90 % / 95 % |
| Demoras (asignación / validación / impresión / calidad) | 50 / 120 / 180 / 80 ms |

## Resultados

Cada ejecución escribe tres archivos en `resultados/`:

| Archivo | Contenido |
|---|---|
| `eventos.csv` | Una fila por cada cambio de estado de una orden: secuencia, tiempo, hilo, etapa, transición e impresora. |
| `elementos.csv` | Una fila por orden, con su estado final, la impresora que usó y cuántas veces pasó por cada etapa. |
| `resumen.properties` | Totales por estado final, duración y verificación de terminación. |

El repositorio incluye los resultados de una ejecución oficial. Con la configuración oficial, los estados finales son siempre **359 APPROVED, 71 REJECTED, 45 PRINT_FAILED y 25 DEFECTIVE**, porque las decisiones de cada etapa son deterministas (`OutcomeDecider`). Lo que cambia entre ejecuciones es qué impresora usa cada orden, los tiempos y el orden de los eventos.

## Estructura del código

```
src/main/java/ar/edu/unc/fcefyn/pcp/tp1/
├── api/                      Clases de la cátedra (no se modifican)
├── app/Main.java             Punto de entrada: lee la configuración y ejecuta la simulación
└── solution/
    ├── ConcurrentSimulation  Crea los recursos, arranca los hilos y coordina la terminación
    ├── model/                Order, Printer, Stage
    ├── monitor/              OrderQueue y PrinterFarm: los monitores (wait/notifyAll)
    ├── log/EventLog          Transiciones de las órdenes y filas de eventos.csv
    ├── output/ResultWriter   Escritura de los archivos de resultados
    └── workers/              StageWorker y las cuatro etapas
```

**Cómo funciona, en resumen:**

- **Pipeline de cuatro etapas** conectadas por colas (`OrderQueue`). Cada etapa toma órdenes de su cola, las procesa y las pasa a la siguiente.
- **Pool de impresoras** (`PrinterFarm`). La asignación espera mientras no haya impresoras disponibles; la validación y la impresión las devuelven.
- **Los workers no sincronizan nada por su cuenta**: toda la exclusión mutua y la espera están dentro de los monitores y del registro de eventos.
- **Terminación en cascada.** El hilo principal espera con `join()` a los hilos de cada etapa y recién entonces cierra la cola siguiente. Un worker termina cuando su cola está cerrada y vacía.

## Tests

`mvn clean test` ejecuta 40 tests:

- **Suite pública** (`publictests/`): configuración, determinismo, resultados, formato de los archivos y restricciones del código.
- **`OrderQueueTest`**: varios productores y consumidores sin pérdidas ni duplicados, y cierre de la cola.
- **`PrinterFarmTest`**: reservas concurrentes sin asignaciones simultáneas, reutilización de una impresora e impresoras fuera de servicio.
- **`InvariantsTest`**: 141 simulaciones en 8 escenarios (una sola impresora, todo rechazado, todas las impresiones fallan, un hilo por etapa, entre otros). En cada una se verifican los 10 invariantes del enunciado sobre `eventos.csv`.

## Documentación

- [`ENUNCIADO.md`](ENUNCIADO.md): enunciado del trabajo.
- [`docs/informe.pdf`](docs/informe.pdf): informe con el análisis de concurrencia, el análisis teórico y los resultados experimentales.
- [`docs/diagrama-clases.png`](docs/diagrama-clases.png): diagrama de clases.
- [`docs/diagrama-secuencia.png`](docs/diagrama-secuencia.png): diagrama de secuencia de la ejecución y terminación de la simulación.
- [`docs/diagrama-secuencia-orden.png`](docs/diagrama-secuencia-orden.png): diagrama de secuencia del ciclo de vida de una orden.

## Entrega

La entrega corresponde al tag `thread-ators-entrega-tp1`.

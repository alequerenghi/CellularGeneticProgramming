# Cellular Genetic Programming Engine

This project implements a Cellular Genetic Algorithm (CGA) and Cellular Genetic Programming (CGP) engine on top of the [Jenetics 8.3](https://jenetics.io) framework.
Unlike the standard Jenetics `Engine`, which assumes a panmictic (fully mixed) population, this engine evolves individuals locally according to a graph-based topology. Each individual interacts only with its neighbors, enabling decentralized and spatially structured evolution.

Cellular evolutionary models naturally preserve diversity, form evolutionary niches, and mimic distributed natural adaptation. They are well suited for large-scale GP, coevolution, and spatial optimization.

---

## Features

* Graph-based spatial population (grid, random graphs, Barabási–Albert, etc.)
* Local parent selection among neighbors only
* Local competition replacement (offspring competes with parent only)
* Parallel single-cell evolution using an `ExecutorService`
* Full compatibility with Jenetics alterers, codecs, constraints, and selectors
* Native support for Genetic Programming with `ProgramGene`
* Works with Jenetics `EvolutionStream` API
* Builder pattern similar to the standard `Engine`

---

## Cellular Evolution Overview

In a cellular evolutionary algorithm, each individual occupies a node of a graph.
At each generation:

1. The node collects a list of neighboring individuals.
2. A local selection operator chooses parents from the neighborhood.
3. Alterers (crossover, mutation, etc.) produce offspring.
4. The offspring competes with the current individual.
5. If the offspring is fitter, it replaces the parent.

This produces spatially structured evolutionary dynamics, maintains diversity, and reduces premature convergence.

---

## Topology

A topology is represented as:

```
node index -> list of neighbor node indices
```

Predefined topologies include:

* Grid neighborhoods (von Neumann, Moore)
* Random graphs
* Barabási–Albert scale-free networks
* Fully connected networks
* User-defined custom graphs

Example:

```java
GraphMap topology = GraphMaps.grid(400);
```

Barabási–Albert example:

```java
GraphMap topology = GraphMaps.barabasiAlbert(200, 3);
```

---

## Building a CellularEngine

The API matches Jenetics' style.

Example for symbolic regression with GP:

```java
CellularEngine<ProgramGene<Double>, Double> engine =
    CellularEngine.builder(problem)
                  .topology(GraphMaps.grid(400))
                  .alterers(
                      new SingleNodeCrossover<>(0.3),
                      new Mutator<>(0.5)
                  )
                  .minimizing()
                  .build();
```

Configurable properties include:

* topology (graph structure)
* alterers
* constraints
* optimization direction
* executor service
* phenotype aging (via EvolutionParams)

---

## Running Evolution

`CellularEngine` returns a standard Jenetics `EvolutionStream`:

```java
Phenotype<ProgramGene<Double>, Double> best =
    engine.stream()
          .limit(1000)
          .collect(EvolutionResult.toBestPhenotype());
```

Or stop when a solution passes a fitness threshold:

```java
var result =
    engine.stream()
          .limit(Limits.byFitnessThreshold(0.01))
          .collect(EvolutionResult.toBestEvolutionResult());
```

Everything else (neighbor selection, local evolution, evaluation) is handled internally.

---


## Internal Workflow

Each generation consists of:

1. Filtering
   Invalid or aged phenotypes are repaired or regenerated.

2. Evaluation
   Fitness values are computed using the supplied `Evaluator`, possibly in parallel.

3. Local Evolution

   * Neighbor gathering
   * Local selection
   * Application of alterers
   * Offspring evaluation
   * Local competition and replacement

4. Aggregation
   A complete new population is produced, matching the topology size.

---

## Project Structure

```
src/main/java/cellular	— Engine and topology implementations
src/main/java/examples	- Regression test
data/              		- Datasets for regression
outputs/				- Test outputs
```

Key source components:

* `CellularEngine` 				— core evolutionary engine
* `GraphMap` and `GraphMaps` 	— topology representations
* `FitnessEvaluator`			- custom parallel evaluator


---

## <p style="textcolor:red">WARNING</p>

The tests take about 40'. `outputs` already contains results for the tests but the number of iterations can be reduced to make the tests run faster.

---

## Requirements

* Java 17 or newer
* Maven 3.8 or newer
* Jenetics 8.3.0

